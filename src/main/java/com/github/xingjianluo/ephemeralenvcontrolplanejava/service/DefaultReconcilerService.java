package com.github.xingjianluo.ephemeralenvcontrolplanejava.service;

import com.github.xingjianluo.ephemeralenvcontrolplanejava.backend.ResourceBackend;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.config.ControlPlaneProperties;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.config.TimeSource;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.model.Environment;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.model.EnvAction;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.model.EnvStateMachine;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.model.EnvStatus;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.model.ResourceHandle;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.repo.EnvironmentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Post-restart / partial-failure convergence.
 *
 * <ul>
 *   <li><b>orphans</b>: backend resources whose envId belongs to no environment
 *       that still owns resources (anything terminal, missing, or mid-release)
 *       are deallocated. Live READY/PROVISIONING environments are untouched.</li>
 *   <li><b>stuck releases</b>: environments found in RELEASING (crash between
 *       transition and completion) are driven to RELEASED; quota is returned
 *       exactly once and backend calls stay idempotent.</li>
 * </ul>
 * Both passes are bounded by {@code reconcileBatchSize} so memory/time use is
 * capped regardless of backend size.
 */
public class DefaultReconcilerService implements ReconcilerService {

    private static final Logger log = LoggerFactory.getLogger(DefaultReconcilerService.class);
    public static final String SYSTEM_RECONCILER = "system:reconciler";

    private final EnvironmentRepository repository;
    private final ResourceBackend backend;
    private final QuotaService quotaService;
    private final AuditService auditService;
    private final TimeSource timeSource;
    private final ControlPlaneProperties properties;

    public DefaultReconcilerService(EnvironmentRepository repository,
                                    ResourceBackend backend,
                                    QuotaService quotaService,
                                    AuditService auditService,
                                    TimeSource timeSource,
                                    ControlPlaneProperties properties) {
        this.repository = repository;
        this.backend = backend;
        this.quotaService = quotaService;
        this.auditService = auditService;
        this.timeSource = timeSource;
        this.properties = properties;
    }

    @Override
    public int reconcileOrphans() {
        int batch = properties.getReconcileBatchSize();
        Set<String> ownerEnvIds = new HashSet<>();
        for (Environment e : repository.findAll()) {
            // Only envs that may legitimately hold backend resources protect them.
            if (e.status() == EnvStatus.READY
                    || e.status() == EnvStatus.PROVISIONING
                    || e.status() == EnvStatus.REQUESTED
                    || e.status() == EnvStatus.EXPIRED) {
                ownerEnvIds.add(e.id());
            }
        }

        List<ResourceHandle> all = backend.listAll();
        int touched = 0;
        int removed = 0;
        for (ResourceHandle h : all) {
            if (touched >= batch) {
                log.info("reconcile orphans batch-limit hit batch={} removed={}; remainder deferred",
                        batch, removed);
                break;
            }
            touched++;
            if (ownerEnvIds.contains(h.envId())) {
                continue; // belongs to a live, in-lease environment: never delete
            }
            backend.deallocate(h);
            removed++;
            log.info("reconcile removed orphan resource={} env={}",
                    h.resourceId(), h.envId());
        }
        log.info("reconcile orphans complete scanned={}/{} removed={}",
                touched, all.size(), removed);
        return removed;
    }

    @Override
    public int reconcileStuckReleases() {
        int batch = properties.getReconcileBatchSize();
        int touched = 0;
        int completed = 0;
        for (Environment e : repository.findAll()) {
            if (touched >= batch) {
                break;
            }
            if (e.status() != EnvStatus.RELEASING) {
                continue;
            }
            touched++;
            synchronized (repository.lockFor(e.id())) {
                Environment current = repository.findById(e.id()).orElse(null);
                if (current == null || current.status() != EnvStatus.RELEASING) {
                    continue;
                }
                finishStuckRelease(current);
                completed++;
            }
        }
        log.info("reconcile stuck-releases complete touched={} completed={}",
                touched, completed);
        return completed;
    }

    private void finishStuckRelease(Environment env) {
        Instant now = timeSource.now();
        // Idempotent deallocation of anything the interrupted pass left behind.
        List<ResourceHandle> handles = backend.listForEnv(env.id());
        for (ResourceHandle h : handles) {
            backend.deallocate(h);
        }
        env.clearResources();
        // RELEASING still holds quota; returning it removes ghost occupation.
        quotaService.release(env.tenantId(), env.resourceUnits());

        EnvStateMachine.requireTransition(EnvStatus.RELEASING, EnvStatus.RELEASED);
        env.transition(EnvStatus.RELEASED, now);
        auditService.record(env.tenantId(), env.id(), SYSTEM_RECONCILER,
                EnvAction.COMPLETE_RELEASE, EnvStatus.RELEASING, EnvStatus.RELEASED,
                "reconciled interrupted release, resources=" + handles.size());
        repository.save(env);
        repository.delete(env.id());
        log.info("reconcile completed stuck release env={} cleanedResources={}",
                env.id(), handles.size());
    }
}
