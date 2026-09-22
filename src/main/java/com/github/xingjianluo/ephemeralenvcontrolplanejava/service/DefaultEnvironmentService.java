package com.github.xingjianluo.ephemeralenvcontrolplanejava.service;

import com.github.xingjianluo.ephemeralenvcontrolplanejava.backend.BackendException;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.backend.ResourceBackend;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.config.ControlPlaneProperties;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.config.TimeSource;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.error.ApiException;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.error.ErrorCode;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.model.Environment;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.model.EnvAction;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.model.EnvStateMachine;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.model.EnvStatus;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.model.Lease;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.model.ResourceHandle;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.model.Subject;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.repo.EnvironmentRepository;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.repo.InMemoryEnvironmentRepository;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.template.RenderedTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Core lifecycle orchestration.
 *
 * <p>Concurrency model: every operation on an environment runs while holding
 * that environment's stable striped lock (the lock key is derived from
 * tenant+name, which is constant even after the RELEASED row is deleted).
 * Quota reservation/release is atomic per tenant inside {@link QuotaService}.
 * Renewal vs expiry recycling therefore linearizes: either the new lease is
 * committed before RELEASING, or recycling wins and renewal is rejected with
 * a deterministic EXPIRED/ILLEGAL_TRANSITION error — never both.</p>
 */
public class DefaultEnvironmentService implements EnvironmentService {

    private static final Logger log = LoggerFactory.getLogger(DefaultEnvironmentService.class);
    private static final Pattern NAME = Pattern.compile("[a-z0-9][a-z0-9-]{1,38}[a-z0-9]");
    private static final long MAX_TTL_SECONDS = Duration.ofHours(24).toSeconds();
    private static final long MIN_TTL_SECONDS = 60;
    public static final String SYSTEM_REAPER = "system:reaper";

    private final EnvironmentRepository repository;
    private final TemplateService templateService;
    private final QuotaService quotaService;
    private final AuditService auditService;
    private final AccessService accessService;
    private final ResourceBackend backend;
    private final TimeSource timeSource;
    private final ControlPlaneProperties properties;

    public DefaultEnvironmentService(EnvironmentRepository repository,
                                     TemplateService templateService,
                                     QuotaService quotaService,
                                     AuditService auditService,
                                     AccessService accessService,
                                     ResourceBackend backend,
                                     TimeSource timeSource,
                                     ControlPlaneProperties properties) {
        this.repository = repository;
        this.templateService = templateService;
        this.quotaService = quotaService;
        this.auditService = auditService;
        this.accessService = accessService;
        this.backend = backend;
        this.timeSource = timeSource;
        this.properties = properties;
    }

    // ------------------------------------------------------------------ apply

    @Override
    public ApplyResult apply(ApplyCommand command) {
        Subject subject = command.subject();
        String name = requireValidName(command.name());
        if (subject == null) {
            throw new ApiException(ErrorCode.UNAUTHENTICATED, "authentication required");
        }
        if (command.templateId() == null || command.templateId().isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "templateId is required");
        }
        String version = command.templateVersion() == null || command.templateVersion().isBlank()
                ? "1" : command.templateVersion();
        long ttl = resolveTtl(command.requestedTtlSeconds());

        String envId = InMemoryEnvironmentRepository
                .deterministicId(subject.tenantId(), name);
        synchronized (repository.lockFor(envId)) {
            Environment existing = repository.findById(envId).orElse(null);
            if (existing != null) {
                if (existing.status() == EnvStatus.RELEASED) {
                    // Fully recycled name is free to apply again.
                    repository.delete(envId);
                } else {
                    // Idempotent: same identity returns the same environment,
                    // regardless of whether it is live, FAILED or EXPIRED.
                    log.info("apply idempotent-hit principal={} tenant={} name={} status={}",
                            subject.principal(), subject.tenantId(), name, existing.status());
                    return new ApplyResult(existing, false);
                }
            }

            // Render before reserving anything: template errors must not consume quota.
            RenderedTemplate rendered = templateService.render(
                    command.templateId(), version, command.parameters());

            // Atomic tenant quota gate; races lose here with QUOTA_EXCEEDED.
            quotaService.reserve(subject.tenantId(), rendered.resourceUnits());

            Instant now = timeSource.now();
            Environment env = new Environment(envId, subject.tenantId(), name,
                    subject.principal(), rendered.templateId(), rendered.version(),
                    rendered.resourceUnits(), EnvStatus.REQUESTED,
                    new Lease(now, now.plusSeconds(ttl)), now);
            repository.save(env);
            auditService.record(subject.tenantId(), envId, subject.principal(),
                    EnvAction.APPLY, null, EnvStatus.REQUESTED, "apply requested");
            log.info("apply reserved principal={} tenant={} name={} units={} ttl={}s",
                    subject.principal(), subject.tenantId(), name,
                    rendered.resourceUnits(), ttl);

            provision(env, subject.principal(), rendered, now);
            return new ApplyResult(repository.findById(envId).orElse(env), true);
        }
    }

    private void provision(Environment env, String actor, RenderedTemplate rendered,
                           Instant start) {
        transitionTo(env, EnvStatus.PROVISIONING, actor, EnvAction.START_PROVISION,
                "provisioning started");
        try {
            for (int i = 0; i < rendered.resourceUnits(); i++) {
                ResourceHandle handle = backend.allocate(env.id(), "compute");
                env.addResource(handle);
            }
            env.attachSensitiveConfig(rendered.sensitiveConfig());
            transitionTo(env, EnvStatus.READY, actor, EnvAction.MARK_READY,
                    "provisioning complete, units=" + rendered.resourceUnits());
            log.info("provision ok env={} units={} (secrets never logged)",
                    env.id(), rendered.resourceUnits());
        } catch (BackendException e) {
            // Partial failure: keep a queryable FAILED state, no success illusion.
            rollbackResources(env);
            quotaService.release(env.tenantId(), env.resourceUnits());
            env.fail(e.getMessage(), timeSource.now());
            repository.save(env);
            auditService.record(env.tenantId(), env.id(), actor, EnvAction.FAIL,
                    EnvStatus.PROVISIONING, EnvStatus.FAILED, e.getMessage());
            log.warn("provision FAILED env={} reason='{}' cleanedUnits={}",
                    env.id(), e.getMessage(), env.resources().size());
        }
    }

    private void rollbackResources(Environment env) {
        List<ResourceHandle> handles = env.resources();
        for (ResourceHandle h : handles) {
            try {
                backend.deallocate(h);
            } catch (RuntimeException cleanupError) {
                // Surface in logs with a distinguishable message, never swallow.
                log.error("cleanup error during rollback env={} resource={} reason='{}'",
                        env.id(), h.resourceId(), cleanupError.getMessage());
            }
        }
        env.clearResources();
    }

    // ------------------------------------------------------------------- get

    @Override
    public Environment get(String principal, String tenantId, String envId,
                          boolean includeSecrets) {
        Subject subject = accessService.requireAuthenticated(principal);
        Environment env = requireEnv(tenantId, envId);
        if (includeSecrets) {
            accessService.requireSensitiveView(subject, env);
        } else {
            accessService.requireView(subject, env);
        }
        log.info("get principal={} env={} secretsRequested={} allowed={}",
                principal, env.id(), includeSecrets, includeSecrets);
        return env;
    }

    // ----------------------------------------------------------------- renew

    @Override
    public Environment renew(String principal, String tenantId, String envId,
                             Long requestedTtlSeconds) {
        Subject subject = accessService.requireAuthenticated(principal);
        long ttl = resolveTtl(requestedTtlSeconds);
        Environment env = requireEnv(tenantId, envId);
        synchronized (repository.lockFor(env.id())) {
            env = repository.findById(env.id()).orElseThrow(() -> gone(tenantId, envId));
            accessService.requireMutate(subject, env);
            Instant now = timeSource.now();

            if (env.status() == EnvStatus.READY) {
                if (env.lease().isExpiredAt(now)) {
                    // Reaper may race; mark expiry deterministically first.
                    EnvStateMachine.requireTransition(EnvStatus.READY, EnvStatus.EXPIRED);
                    env.transition(EnvStatus.EXPIRED, now);
                    repository.save(env);
                    auditService.record(tenantId, env.id(), SYSTEM_REAPER,
                            EnvAction.EXPIRE, EnvStatus.READY, EnvStatus.EXPIRED,
                            "lease expired before renewal");
                    throw new ApiException(ErrorCode.EXPIRED,
                            "environment already expired; create a new environment");
                }
                env.renewLease(now.plusSeconds(ttl), now);
                repository.save(env);
                auditService.record(tenantId, env.id(), subject.principal(),
                        EnvAction.RENEW, EnvStatus.READY, EnvStatus.READY,
                        "renewed ttl=" + ttl + "s");
                log.info("renew ok principal={} env={} newExpiry={} at={}",
                        principal, env.id(), env.lease().expiresAt(), now);
                return env;
            }
            if (env.status() == EnvStatus.EXPIRED) {
                throw new ApiException(ErrorCode.EXPIRED,
                        "environment expired and cannot be renewed");
            }
            throw new ApiException(ErrorCode.ILLEGAL_TRANSITION,
                    "cannot renew environment in status " + env.status());
        }
    }

    // --------------------------------------------------------------- recycle

    @Override
    public void recycle(String principal, String tenantId, String envId, String reason) {
        Subject subject = accessService.requireAuthenticated(principal);
        // DELETE is idempotent: a missing/already-released env is a success no-op.
        Environment existing = repository.findById(envId).orElse(null);
        if (existing == null) {
            log.info("recycle idempotent-noop principal={} env={} (absent)", principal, envId);
            return;
        }
        synchronized (repository.lockFor(existing.id())) {
            Environment env = repository.findById(existing.id()).orElse(null);
            if (env == null || env.status() == EnvStatus.RELEASED) {
                log.info("recycle idempotent-noop principal={} env={}", principal, envId);
                return;
            }
            accessService.requireMutate(subject, env);
            doRelease(env, subject.principal(), reason == null ? "requested" : reason);
        }
    }

    /**
     * Internal release used both by user recycling and the expiry reaper.
     * Caller must hold the env lock and (for the reaper) skip access checks.
     */
    public void releaseAsSystem(Environment lockedEnv, String actor, String reason) {
        synchronized (repository.lockFor(lockedEnv.id())) {
            Environment env = repository.findById(lockedEnv.id()).orElse(null);
            if (env == null || env.status() == EnvStatus.RELEASED) {
                return;
            }
            doRelease(env, actor, reason);
        }
    }

    private void doRelease(Environment env, String actor, String reason) {
        Instant now = timeSource.now();
        EnvStatus start = env.status();
        // Capture quota ownership BEFORE any transition: READY/EXPIRED/RELEASING
        // all hold quota, and the quota must be returned exactly once.
        boolean heldQuota = env.status().holdsQuota();

        if (start == EnvStatus.READY && env.lease().isExpiredAt(now)) {
            EnvStateMachine.requireTransition(EnvStatus.READY, EnvStatus.EXPIRED);
            env.transition(EnvStatus.EXPIRED, now);
            repository.save(env);
            auditService.record(env.tenantId(), env.id(), SYSTEM_REAPER,
                    EnvAction.EXPIRE, EnvStatus.READY, EnvStatus.EXPIRED,
                    "lease expired");
            start = EnvStatus.EXPIRED;
        }

        EnvStateMachine.requireTransition(env.status(), EnvStatus.RELEASING);
        env.transition(EnvStatus.RELEASING, now);
        repository.save(env);
        auditService.record(env.tenantId(), env.id(), actor, EnvAction.START_RELEASE,
                start, EnvStatus.RELEASING, reason);

        int released = 0;
        for (ResourceHandle h : env.resources()) {
            // Backend deallocate is idempotent; duplicate release cannot double-free.
            backend.deallocate(h);
            released++;
        }
        env.clearResources();

        if (heldQuota) {
            quotaService.release(env.tenantId(), env.resourceUnits());
        }
        EnvStateMachine.requireTransition(EnvStatus.RELEASING, EnvStatus.RELEASED);
        env.transition(EnvStatus.RELEASED, now);
        auditService.record(env.tenantId(), env.id(), actor, EnvAction.COMPLETE_RELEASE,
                EnvStatus.RELEASING, EnvStatus.RELEASED,
                "released resources=" + released);
        repository.save(env);
        repository.delete(env.id());
        log.info("recycle ok actor={} env={} resources={} quotaReturned={}",
                actor, env.id(), released, heldQuota);
    }

    // ------------------------------------------------------------------ list

    @Override
    public List<Environment> list(String principal, String tenantId, int limit) {
        Subject subject = accessService.requireAuthenticated(principal);
        if (limit <= 0) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "limit must be positive");
        }
        if (limit > properties.getBulkQueryLimit()) {
            throw new ApiException(ErrorCode.BATCH_LIMIT_EXCEEDED,
                    "limit " + limit + " exceeds maximum " + properties.getBulkQueryLimit());
        }
        List<Environment> all = repository.findByTenant(subject.tenantId());
        log.info("list principal={} tenant={} matched={} returning<= {}",
                principal, tenantId, all.size(), limit);
        return all.size() <= limit ? all : all.subList(0, limit);
    }

    // --------------------------------------------------------------- helpers

    private void transitionTo(Environment env, EnvStatus to, String actor,
                              EnvAction action, String reason) {
        EnvStateMachine.requireTransition(env.status(), to);
        EnvStatus from = env.status();
        env.transition(to, timeSource.now());
        repository.save(env);
        auditService.record(env.tenantId(), env.id(), actor, action, from, to, reason);
    }

    private Environment requireEnv(String tenantId, String envId) {
        Environment env = repository.findById(envId).orElse(null);
        if (env == null || !Objects.equals(env.tenantId(), tenantId)) {
            // Same generic 404 regardless of tenant mismatch: no existence leak.
            throw gone(tenantId, envId);
        }
        return env;
    }

    private static ApiException gone(String tenantId, String envId) {
        return new ApiException(ErrorCode.ENV_NOT_FOUND,
                "environment not found: " + envId);
    }

    private static String requireValidName(String name) {
        if (name == null || !NAME.matcher(name).matches()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR,
                    "name must match [a-z0-9][a-z0-9-]{1,38}[a-z0-9]");
        }
        return name;
    }

    private static long resolveTtl(Long requested) {
        long ttl = requested == null ? 3600 : requested;
        if (ttl < MIN_TTL_SECONDS || ttl > MAX_TTL_SECONDS) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR,
                    "ttlSeconds must be between " + MIN_TTL_SECONDS
                            + " and " + MAX_TTL_SECONDS);
        }
        return ttl;
    }
}
