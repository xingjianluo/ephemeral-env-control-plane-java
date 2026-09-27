package com.github.xingjianluo.ephemeralenvcontrolplanejava.service;

import com.github.xingjianluo.ephemeralenvcontrolplanejava.backend.ResourceBackend;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.config.ControlPlaneProperties;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.error.BatchLimitExceededException;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.error.ControlPlaneException;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.error.InvalidStateException;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.error.NameConflictException;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.error.NotFoundException;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.error.ValidationException;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.model.ActionType;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.model.Environment;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.model.EnvironmentStatus;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.model.RenderedConfig;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.model.ResourceSpec;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.model.Subject;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.repo.EnvironmentRepository;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.web.dto.ApplyRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 环境生命周期编排服务（详见类级不变量注释）。
 *
 * 并发模型：同租户所有写操作都在 QuotaService 提供的租户锁内串行；
 * 不同租户使用不同锁，互不阻塞。租户锁内同时完成“配额校验+占用”与
 * 环境状态落库，check-then-act 不可被交错。
 */
@Service
public class EnvironmentService {

    private static final Logger log = LoggerFactory.getLogger(EnvironmentService.class);

    private final EnvironmentRepository repository;
    private final QuotaService quotaService;
    private final ResourceBackend backend;
    private final TemplateService templateService;
    private final AccessControlService accessControlService;
    private final AuditService auditService;
    private final ControlPlaneProperties properties;

    private Clock clock = Clock.systemUTC();

    public EnvironmentService(EnvironmentRepository repository,
                              QuotaService quotaService,
                              ResourceBackend backend,
                              TemplateService templateService,
                              AccessControlService accessControlService,
                              AuditService auditService,
                              ControlPlaneProperties properties) {
        this.repository = repository;
        this.quotaService = quotaService;
        this.backend = backend;
        this.templateService = templateService;
        this.accessControlService = accessControlService;
        this.auditService = auditService;
        this.properties = properties;
    }

    /** 测试可替换时钟（生产默认系统 UTC 时钟）。 */
    public void setClock(Clock clock) {
        this.clock = clock;
    }

    long now() {
        return clock.millis();
    }

    // ---------------------------------------------------------------- 申请

    public Environment apply(Subject actor, ApplyRequest request) {
        ValidateResult vr = validateApply(request);
        // 模板严格渲染在取配额之前：版本/字段不兼容不消耗任何配额。
        RenderedConfig rendered = templateService.render(vr.templateVersion, vr.overrides);

        synchronized (quotaService.lockFor(vr.tenantId)) {
            Environment existing = repository.find(vr.tenantId, vr.name).orElse(null);
            if (existing != null && existing.getStatus() != EnvironmentStatus.RELEASED) {
                return handleIdempotentApply(actor, existing, vr);
            }
            long ttlMillis = vr.ttlSeconds * 1000L;
            // 先原子占用配额（不足直接拒绝），成功后才允许环境落库：
            // 被配额拒绝的请求绝不留下 PENDING 幽灵记录/幽灵占用。
            int cpu = vr.specs.stream().mapToInt(ResourceSpec::cpu).sum();
            int mem = vr.specs.stream().mapToInt(ResourceSpec::memoryMb).sum();
            quotaService.reserve(vr.tenantId, cpu, mem);
            Environment env;
            try {
                env = Environment.createPending(
                        UUID.randomUUID().toString(), vr.tenantId, vr.name, vr.owner,
                        vr.templateVersion, now(), ttlMillis, vr.specs);
                repository.save(env);
                auditService.record(actor, env, ActionType.APPLY,
                        "apply ttlSeconds=" + vr.ttlSeconds, null, EnvironmentStatus.PENDING);
            } catch (RuntimeException e) {
                // 落库前发生意外：归还已占用配额，保持无幽灵占用。
                quotaService.release(vr.tenantId, cpu, mem);
                throw e;
            }
            return provision(actor, env, rendered);
        }
    }

    /** 重复申请：同参数幂等返回；不同参数占用同名 -> 名称冲突（可区分原因）。 */
    private Environment handleIdempotentApply(Subject actor, Environment existing, ValidateResult vr) {
        accessControlService.require(actor, existing, Permission.VIEW_ENV);
        if (!fingerprintOf(existing).equals(vr.fingerprint)) {
            throw new NameConflictException(
                    "environment name already in use with different parameters: " + vr.name);
        }
        log.info("idempotent apply tenant={} name={} actor={} -> status={}",
                vr.tenantId, vr.name, actor.name(), existing.getStatus());
        return existing;
    }

    private record ValidateResult(String tenantId, String name, String owner, String templateVersion,
                                  long ttlSeconds, List<ResourceSpec> specs,
                                  Map<String, Object> overrides, String fingerprint) {
    }

    private ValidateResult validateApply(ApplyRequest req) {
        String tenant = requireToken(req.getTenantId(), "tenantId");
        String name = requireName(req.getName(), "name");
        String owner = requireToken(req.getOwner(), "owner");
        String version = requireToken(req.getTemplateVersion(), "templateVersion");
        long ttl = req.getTtlSeconds() == null ? properties.getLease().getDefaultTtlSeconds() : req.getTtlSeconds();
        if (ttl <= 0 || ttl > properties.getLease().getMaxTtlSeconds()) {
            throw new ValidationException(
                    "ttlSeconds must be in [1," + properties.getLease().getMaxTtlSeconds() + "], got " + ttl);
        }
        if (req.getResources() == null || req.getResources().isEmpty()) {
            throw new ValidationException("resources must not be empty");
        }
        List<ResourceSpec> specs = new ArrayList<>();
        for (ResourceSpec s : req.getResources()) {
            if (s == null || s.kind() == null || s.kind().isBlank()) {
                throw new ValidationException("resource.kind is required");
            }
            if (s.cpu() <= 0 || s.memoryMb() <= 0) {
                throw new ValidationException(
                        "resource cpu/memoryMb must be positive (kind=" + s.kind() + ")");
            }
            specs.add(new ResourceSpec(s.kind(), s.image() == null ? "" : s.image(), s.cpu(), s.memoryMb()));
        }
        String fingerprint = owner + "|" + version + "|" + ttl + "|" + specs;
        return new ValidateResult(tenant, name, owner, version, ttl, List.copyOf(specs),
                req.getConfigOverrides() == null ? Map.of() : req.getConfigOverrides(), fingerprint);
    }

    private String fingerprintOf(Environment env) {
        return env.getOwner() + "|" + env.getTemplateVersion() + "|"
                + ((env.getExpiresAtMillis() - env.getCreatedAtMillis()) / 1000)
                + "|" + env.getSpecs();
    }

    private static String requireToken(String v, String field) {
        if (v == null || v.isBlank()) {
            throw new ValidationException(field + " is required");
        }
        if (v.contains(":")) {
            throw new ValidationException(field + " must not contain ':'");
        }
        return v;
    }

    private static String requireName(String v, String field) {
        String token = requireToken(v, field);
        if (token.length() > 64 || !token.matches("[A-Za-z0-9._-]+")) {
            throw new ValidationException(
                    field + " must match [A-Za-z0-9._-]{1,64}, got: " + token);
        }
        return token;
    }

    /**
     * 在租户锁内执行：先原子占用配额，再逐个分配后端资源。
     * 任一资源分配失败 -> 回滚已分配资源、归还配额、落 FAILED 中间态（绝不标 READY），
     * 并把失败原因抛出给调用方（不留成功假象）。
     */
    private Environment provision(Subject actor, Environment env, RenderedConfig rendered) {
        // 配额已在 apply 中、PENDING 落库之前预留成功（同租户锁内，不会被交错）。
        env.transitionTo(EnvironmentStatus.PROVISIONING);
        repository.save(env);
        auditService.record(actor, env, ActionType.PROVISION, "quota reserved",
                EnvironmentStatus.PENDING, EnvironmentStatus.PROVISIONING);

        List<String> allocated = new ArrayList<>();
        try {
            for (ResourceSpec spec : env.getSpecs()) {
                allocated.add(backend.allocate(env.getId(), spec).resourceId());
                env.setResourceIds(List.copyOf(allocated));
                repository.save(env);
            }
        } catch (ControlPlaneException e) {
            // 部分失败：尽力回滚后端资源（回滚失败也忽略，交给收敛器），保留可查询的 FAILED 态。
            safeReleaseBackend(env, allocated);
            env.setResourceIds(List.of());
            env.setFailureReason(e.getMessage());
            env.transitionTo(EnvironmentStatus.FAILED);
            releaseQuotaOnce(env);
            repository.save(env);
            auditService.record(actor, env, ActionType.PROVISION,
                    "provisioning failed: " + e.getMessage(),
                    EnvironmentStatus.PROVISIONING, EnvironmentStatus.FAILED);
            throw e;
        }

        // 全部资源就绪：固化配置快照（敏感值分离存放），模板升级不追溯影响。
        env.setConfigValues(maskSecret(rendered));
        env.setSecretValue(templateService.extractSecret(rendered));
        env.setTemplateVersion(rendered.templateVersion());
        env.transitionTo(EnvironmentStatus.READY);
        repository.save(env);
        auditService.record(actor, env, ActionType.MARK_READY, "all resources allocated",
                EnvironmentStatus.PROVISIONING, EnvironmentStatus.READY);
        return env;
    }

    private Map<String, Object> maskSecret(RenderedConfig rendered) {
        Map<String, Object> safe = new LinkedHashMap<>(rendered.values());
        safe.put(rendered.secretField(), "***");
        return Map.copyOf(safe);
    }

    private void safeReleaseBackend(Environment env, List<String> resourceIds) {
        for (String rid : resourceIds) {
            try {
                backend.release(rid);
            } catch (Exception ex) {
                log.warn("best-effort rollback release failed env={} resource={}: {}",
                        env.getName(), rid, ex.toString());
            }
        }
    }

    /** 归还配额恰好一次（quotaReleased 守卫，防止失败/收敛路径幽灵占用或重复归还）。 */
    private void releaseQuotaOnce(Environment env) {
        if (!env.isQuotaReleased()) {
            quotaService.release(env.getTenantId(), env.getTotalCpu(), env.getTotalMemoryMb());
            env.setQuotaReleased(true);
        }
    }

    // ---------------------------------------------------------------- 查询

    public Environment get(Subject actor, String tenantId, String name) {
        Environment env = requireEnv(tenantId, name);
        accessControlService.require(actor, env, Permission.VIEW_ENV);
        return env;
    }

    public String getSecret(Subject actor, String tenantId, String name) {
        Environment env = requireEnv(tenantId, name);
        accessControlService.require(actor, env, Permission.VIEW_SENSITIVE_CONFIG);
        if (env.getStatus() != EnvironmentStatus.READY || env.getSecretValue() == null) {
            throw new InvalidStateException(
                    "secret is only available for READY environments, current=" + env.getStatus());
        }
        log.info("secret accessed actor={} env={}", actor.name(), env.getName());
        return env.getSecretValue();
    }

    public List<Environment> list(Subject actor, int limit) {
        if (limit <= 0) {
            throw new BatchLimitExceededException("limit must be positive");
        }
        if (limit > properties.getListMaxLimit()) {
            throw new BatchLimitExceededException(
                    "limit " + limit + " exceeds configured max " + properties.getListMaxLimit());
        }
        return repository.list(actor.tenantId(), limit);
    }

    private Environment requireEnv(String tenantId, String name) {
        return repository.find(tenantId, name)
                .orElseThrow(() -> new NotFoundException(
                        "environment not found: " + name + " (tenant=" + tenantId + ")"));
    }

    // ---------------------------------------------------------------- 续期

    public Environment renew(Subject actor, String tenantId, String name, long ttlSeconds) {
        if (ttlSeconds <= 0 || ttlSeconds > properties.getLease().getMaxTtlSeconds()) {
            throw new ValidationException(
                    "ttlSeconds must be in [1," + properties.getLease().getMaxTtlSeconds() + "]");
        }
        synchronized (quotaService.lockFor(tenantId)) {
            Environment env = requireEnv(tenantId, name);
            accessControlService.require(actor, env, Permission.RENEW);
            long ts = now();
            // 关键不变量：到期判定与回收在同一临界区内，与并发续期严格串行。
            // 情形 A：先到期 -> 立即完成回收，续期失败（不会先续上又被回收）。
            if (env.getStatus() == EnvironmentStatus.READY && ts >= env.getExpiresAtMillis()) {
                releaseInternal(actor, env, "expired before renew (at " + ts + ")", ActionType.EXPIRE);
                throw new InvalidStateException(
                        "environment already expired and has been released: " + name);
            }
            if (env.isFinal()) {
                throw new InvalidStateException("environment already released: " + name);
            }
            // 情形 B：未到期 -> 续期成功；后到的扫描在锁内看到新到期时间，跳过。
            long newExpiry = env.renew(ts, ttlSeconds * 1000L);
            repository.save(env);
            auditService.record(actor, env, ActionType.RENEW,
                    "renew ttlSeconds=" + ttlSeconds + " newExpiryAt=" + newExpiry,
                    EnvironmentStatus.READY, EnvironmentStatus.READY);
            return env;
        }
    }

    // ---------------------------------------------------------------- 挂起 / 恢复

    /**
     * 挂起：READY -> SUSPENDING -> SUSPENDED。
     * 释放全部后端计算资源并归还配额（同租户其他环境可复用），
     * 但保留环境身份、归属与已固化的配置快照；幂等：已 SUSPENDED 直接返回。
     */
    public Environment suspend(Subject actor, String tenantId, String name, String reason) {
        synchronized (quotaService.lockFor(tenantId)) {
            Environment env = requireEnv(tenantId, name);
            accessControlService.require(actor, env, Permission.SUSPEND);
            if (env.getStatus() == EnvironmentStatus.SUSPENDED) {
                // 幂等：重复挂起不报错、不重复释放资源/配额。
                log.info("idempotent suspend actor={} env={} (already SUSPENDED)", actor.name(), name);
                return env;
            }
            if (env.getStatus() != EnvironmentStatus.READY) {
                throw new InvalidStateException(
                        "only READY environment can be suspended, current=" + env.getStatus()
                                + " (env=" + name + ")");
            }
            return suspendInternal(actor, env, reason == null ? "manual suspend" : reason,
                    ActionType.SUSPEND);
        }
    }

    /**
     * 恢复：SUSPENDED -> RESUMING -> READY。
     * 在租户锁内先做确定性判定：已到期/保留超限 -> 先完成回收再明确拒绝；
     * 配额不足 -> 明确拒绝且环境稳定停在 SUSPENDED（无部分恢复，可重试）；
     * 配置快照与模板版本沿用挂起前固化值，新版本模板不追溯影响。
     * 幂等：已 READY 直接返回。
     */
    public Environment resume(Subject actor, String tenantId, String name) {
        synchronized (quotaService.lockFor(tenantId)) {
            Environment env = requireEnv(tenantId, name);
            accessControlService.require(actor, env, Permission.RESUME);
            if (env.getStatus() == EnvironmentStatus.READY) {
                // 幂等：重复恢复不报错、不重复占用配额/资源。
                log.info("idempotent resume actor={} env={} (already READY)", actor.name(), name);
                return env;
            }
            if (env.getStatus() != EnvironmentStatus.SUSPENDED) {
                throw new InvalidStateException(
                        "only SUSPENDED environment can be resumed, current=" + env.getStatus()
                                + " (env=" + name + ")");
            }
            long ts = now();
            // 关键不变量：恢复与回收只能有一个确定结果。到期判定与回收在同一临界区，
            // 先完成回收再拒绝恢复，绝不出现“恢复成功同时又被回收”。
            if (ts >= env.getExpiresAtMillis()) {
                releaseInternal(actor, env, "expired before resume (at " + ts + ")", ActionType.EXPIRE);
                throw new InvalidStateException(
                        "environment already expired and has been released: " + name);
            }
            long retentionMillis = properties.getSuspend().getMaxRetentionSeconds() * 1000L;
            if (env.isSuspendedRetentionExceeded(ts, retentionMillis)) {
                releaseInternal(actor, env,
                        "suspended retention exceeded before resume (maxRetentionSeconds="
                                + properties.getSuspend().getMaxRetentionSeconds() + ")",
                        ActionType.EXPIRE);
                throw new InvalidStateException(
                        "suspended environment exceeded max retention and has been released: " + name);
            }
            // 重新占用配额：不足则配额异常抛出，环境未做任何变更，稳定停在 SUSPENDED。
            quotaService.reserve(tenantId, env.getTotalCpu(), env.getTotalMemoryMb());
            env.setQuotaReleased(false);
            // 后端失败时停留 RESUMING 中间态（配额已占、资源部分在建，状态一致可查），
            // 由收敛器继续推进；此处不归还配额，避免“资源在但配额未占”的反向半更新。
            return resumeInternal(actor, env, ActionType.RESUME);
        }
    }

    /**
     * 调用方必须持有租户锁。释放全部后端资源成功后归还配额并进入 SUSPENDED；
     * 后端失败则停留 SUSPENDING 中间态（配额仍占用、残留资源可查），等待收敛重试。
     */
    Environment suspendInternal(Subject actor, Environment env, String reason, ActionType action) {
        EnvironmentStatus before = env.getStatus();
        if (before != EnvironmentStatus.SUSPENDING) {
            env.transitionTo(EnvironmentStatus.SUSPENDING);
            repository.save(env);
            auditService.record(actor, env, action, reason, before, EnvironmentStatus.SUSPENDING);
        }
        int deleted;
        try {
            deleted = backend.releaseAll(env.getId());
            for (String rid : env.getResourceIds()) {
                try {
                    if (backend.release(rid)) {
                        deleted++;
                    }
                } catch (ControlPlaneException e) {
                    env.setFailureReason(e.getMessage());
                    repository.save(env);
                    throw e;
                }
            }
        } catch (ControlPlaneException e) {
            env.setFailureReason(e.getMessage());
            repository.save(env); // SUSPENDING 残留：可查询的中间态，绝不留成功假象
            auditService.record(actor, env, action,
                    "suspend incomplete, awaiting reconciliation: " + e.getMessage(),
                    EnvironmentStatus.SUSPENDING, EnvironmentStatus.SUSPENDING);
            throw e;
        }
        env.setResourceIds(List.of());
        releaseQuotaOnce(env); // 计算配额让出给同租户其他环境
        env.setFailureReason(null);
        env.setSuspendedAtMillis(now());
        env.transitionTo(EnvironmentStatus.SUSPENDED);
        repository.save(env);
        auditService.record(actor, env, action,
                reason + " (resources released=" + deleted + ", quota returned)",
                EnvironmentStatus.SUSPENDING, EnvironmentStatus.SUSPENDED);
        return env;
    }

    /**
     * 调用方必须持有租户锁且配额已重新占用。按挂起前固化的资源规格重建后端资源
     * （不重新渲染模板：配置快照与模板版本保持挂起前状态）；失败停留 RESUMING。
     */
    Environment resumeInternal(Subject actor, Environment env, ActionType action) {
        EnvironmentStatus before = env.getStatus();
        if (before != EnvironmentStatus.RESUMING) {
            env.transitionTo(EnvironmentStatus.RESUMING);
            repository.save(env);
            auditService.record(actor, env, action,
                    "resume: quota re-reserved, rebuilding resources",
                    before, EnvironmentStatus.RESUMING);
        }
        try {
            allocateMissing(env);
        } catch (ControlPlaneException e) {
            env.setFailureReason(e.getMessage());
            repository.save(env); // RESUMING 残留：可查询的中间态
            auditService.record(actor, env, action,
                    "resume incomplete, awaiting reconciliation: " + e.getMessage(),
                    EnvironmentStatus.RESUMING, EnvironmentStatus.RESUMING);
            throw e;
        }
        env.setFailureReason(null);
        env.setSuspendedAtMillis(0);
        env.transitionTo(EnvironmentStatus.READY);
        repository.save(env);
        auditService.record(actor, env, action,
                "resumed with pinned config snapshot (templateVersion=" + env.getTemplateVersion() + ")",
                EnvironmentStatus.RESUMING, EnvironmentStatus.READY);
        return env;
    }

    /** 逐个补齐缺失的后端资源（按 resourceIds 数量判定进度，幂等可重入）。 */
    private void allocateMissing(Environment env) {
        List<String> allocated = new ArrayList<>(env.getResourceIds());
        List<ResourceSpec> specs = env.getSpecs();
        while (allocated.size() < specs.size()) {
            ResourceSpec spec = specs.get(allocated.size());
            allocated.add(backend.allocate(env.getId(), spec).resourceId());
            env.setResourceIds(List.copyOf(allocated));
            repository.save(env);
        }
    }

    // ---------------------------------------------------------------- 回收

    public Environment release(Subject actor, String tenantId, String name, String reason) {
        synchronized (quotaService.lockFor(tenantId)) {
            Environment env = requireEnv(tenantId, name);
            accessControlService.require(actor, env, Permission.RELEASE);
            if (env.isFinal()) {
                // 重复回收幂等：返回同一终态，不重复释放资源/配额，也不报错。
                log.info("idempotent release actor={} env={} (already RELEASED)", actor.name(), name);
                return env;
            }
            return releaseInternal(actor, env, reason == null ? "manual release" : reason,
                    ActionType.RELEASE);
        }
    }

    /**
     * 调用方必须持有租户锁。释放后端全部资源；全部删除成功才归还配额并进入 RELEASED。
     * 若后端部分失败：停留在 RELEASING 中间态、保留配额与残留资源，等待收敛，
     * 绝不假装成功。资源释放按后端幂等语义可安全重试。
     */
    /**
     * 重启收敛：处理卡住/失败的环境。调用方无需持锁（本方法自行获取租户锁）。
     *
     * - PENDING：后端尚无资源，直接落 FAILED 并归还配额；
     * - PROVISIONING/FAILED：删除全部残留后端资源（幂等），确保配额归还，落 FAILED
     *   （失败中间态可查询，审计原因标明为收敛）；
     * - RELEASING：继续完成释放，成功则 RELEASED，仍失败则保持中间态等待下一轮；
     * - SUSPENDING：继续完成挂起（释放残留资源、归还配额），成功则 SUSPENDED；
     * - RESUMING：若已过期则确定性转为回收；否则补齐缺失资源，成功则 READY；
     * - READY / SUSPENDED / RELEASED：不处理（READY 未过期与 SUSPENDED 都是有效稳定态，
     *   绝不动；过期交给扫描器，RELEASED 的孤立资源由收敛器第一阶段删除）。
     *
     * @return true 表示本轮已安全收敛；false 表示后端仍失败，需下一轮继续。
     */
    public boolean recoverStuck(Environment snapshot, long now) {
        synchronized (quotaService.lockFor(snapshot.getTenantId())) {
            Environment env = repository.findById(snapshot.getId()).orElse(null);
            if (env == null) {
                return true;
            }
            switch (env.getStatus()) {
                case PENDING -> {
                    env.transitionTo(EnvironmentStatus.FAILED);
                    env.setFailureReason("stuck in PENDING recovered at restart");
                    releaseQuotaOnce(env);
                    repository.save(env);
                    auditService.recordSystem(env, ActionType.RECONCILE,
                            "stuck PENDING -> FAILED, quota returned",
                            EnvironmentStatus.PENDING, EnvironmentStatus.FAILED);
                    return true;
                }
                case PROVISIONING, FAILED -> {
                    List<String> remaining = new ArrayList<>(env.getResourceIds());
                    remaining.addAll(backend.findByEnv(env.getId()).stream()
                            .map(r -> r.resourceId()).toList());
                    boolean backendOk = true;
                    int deleted = 0;
                    for (String rid : remaining.stream().distinct().toList()) {
                        try {
                            if (backend.release(rid)) {
                                deleted++;
                            }
                        } catch (ControlPlaneException e) {
                            backendOk = false;
                        }
                    }
                    releaseQuotaOnce(env);
                    env.setResourceIds(List.of());
                    if (backendOk) {
                        if (env.getStatus() == EnvironmentStatus.PROVISIONING) {
                            env.transitionTo(EnvironmentStatus.FAILED);
                        }
                        env.setFailureReason(
                                (env.getFailureReason() == null ? "" : env.getFailureReason())
                                        + " | reconciled: orphan resources removed=" + deleted);
                        repository.save(env);
                        auditService.recordSystem(env, ActionType.RECONCILE,
                                "orphan resources removed=" + deleted + ", quota returned",
                                EnvironmentStatus.PROVISIONING, env.getStatus());
                        return true;
                    }
                    env.setFailureReason("reconciliation incomplete: backend release failed");
                    repository.save(env);
                    auditService.recordSystem(env, ActionType.RECONCILE,
                            "reconciliation blocked by backend failure",
                            env.getStatus(), env.getStatus());
                    return false;
                }
                case RELEASING -> {
                    try {
                        releaseInternal(null, env, "reconcile stuck RELEASING", ActionType.RECONCILE);
                        return true;
                    } catch (ControlPlaneException e) {
                        return false;
                    }
                }
                case SUSPENDING -> {
                    try {
                        suspendInternal(null, env, "reconcile stuck SUSPENDING", ActionType.RECONCILE);
                        return true;
                    } catch (ControlPlaneException e) {
                        return false;
                    }
                }
                case RESUMING -> {
                    // 恢复途中已到期：确定性地转为回收，不允许“恢复成功同时又被回收”。
                    // 判定统一使用服务时钟（与续期/扫描一致），不使用调用方传入的时间。
                    if (now() >= env.getExpiresAtMillis()) {
                        try {
                            releaseInternal(null, env,
                                    "expired while RESUMING, reconciled to release", ActionType.EXPIRE);
                            return true;
                        } catch (ControlPlaneException e) {
                            return false;
                        }
                    }
                    try {
                        resumeInternal(null, env, ActionType.RECONCILE);
                        return true;
                    } catch (ControlPlaneException e) {
                        return false;
                    }
                }
                default -> {
                    return true; // READY / SUSPENDED / RELEASED 不动
                }
            }
        }
    }

    /**
     * 到期判定 + 回收的原子步骤（调用方在租户锁内，本方法再加租户锁为可重入同一监视器）。
     * 必须从仓库重新读取环境，确保看到并发续期/恢复的最新状态：
     * 扫描器遍历的是快照，真正判定以锁内最新状态为准。
     * 覆盖 READY 与 SUSPENDED：挂起不免回收；SUSPENDED 还受最长保留时间约束。
     *
     * @return true 表示本次完成回收；false 表示未到期或已被并发续期/恢复/回收。
     */
    public boolean expireIfDue(Environment snapshot) {
        synchronized (quotaService.lockFor(snapshot.getTenantId())) {
            Environment fresh = repository.findById(snapshot.getId()).orElse(null);
            if (fresh == null || fresh.isFinal()) {
                return false;
            }
            EnvironmentStatus st = fresh.getStatus();
            if (st != EnvironmentStatus.READY && st != EnvironmentStatus.SUSPENDED) {
                return false;
            }
            long ts = now();
            String reason;
            if (ts >= fresh.getExpiresAtMillis()) {
                reason = "lease expired at " + fresh.getExpiresAtMillis()
                        + (st == EnvironmentStatus.SUSPENDED ? " (while SUSPENDED)" : "");
            } else if (st == EnvironmentStatus.SUSPENDED
                    && fresh.isSuspendedRetentionExceeded(ts,
                            properties.getSuspend().getMaxRetentionSeconds() * 1000L)) {
                reason = "suspended retention exceeded (suspendedAt=" + fresh.getSuspendedAtMillis()
                        + ", maxRetentionSeconds=" + properties.getSuspend().getMaxRetentionSeconds() + ")";
            } else {
                // 已被并发续期/恢复：绝不能回收（确定性结果：续期/恢复赢，扫描跳过）。
                return false;
            }
            try {
                releaseInternal(null, fresh, reason, ActionType.EXPIRE);
            } catch (ControlPlaneException e) {
                // RELEASING 中间态已落库，下一轮扫描/收敛继续，绝不返回成功假象。
                log.warn("expire release incomplete env={}: {}", fresh.getName(), e.getMessage());
                return false;
            }
            return true;
        }
    }

    Environment releaseInternal(Subject actor, Environment env, String reason, ActionType action) {
        EnvironmentStatus before = env.getStatus();
        if (before != EnvironmentStatus.RELEASING) {
            env.transitionTo(EnvironmentStatus.RELEASING);
            repository.save(env);
            auditService.record(actor, env, action, reason, before, EnvironmentStatus.RELEASING);
        }
        int deleted;
        try {
            deleted = backend.releaseAll(env.getId());
        } catch (ControlPlaneException e) {
            env.setFailureReason(e.getMessage());
            repository.save(env); // RELEASING 残留：可查询的中间态
            auditService.record(actor, env, action,
                    "release incomplete, awaiting reconciliation: " + e.getMessage(),
                    EnvironmentStatus.RELEASING, EnvironmentStatus.RELEASING);
            throw e;
        }
        // 记录在 env 上但后端已不存在的资源（崩溃残留记录）也算安全删除，逐条幂等补偿。
        for (String rid : env.getResourceIds()) {
            try {
                if (backend.release(rid)) {
                    deleted++;
                }
            } catch (ControlPlaneException e) {
                env.setFailureReason(e.getMessage());
                repository.save(env);
                throw e;
            }
        }
        env.setResourceIds(List.of());
        env.setSecretValue(null); // 销毁即清除敏感值
        releaseQuotaOnce(env);
        env.setFailureReason(null);
        env.transitionTo(EnvironmentStatus.RELEASED);
        repository.save(env);
        auditService.record(actor, env, action,
                reason + " (resources released=" + deleted + ")",
                EnvironmentStatus.RELEASING, EnvironmentStatus.RELEASED);
        return env;
    }
}
