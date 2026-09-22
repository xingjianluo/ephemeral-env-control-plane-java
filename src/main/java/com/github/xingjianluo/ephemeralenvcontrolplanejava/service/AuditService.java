package com.github.xingjianluo.ephemeralenvcontrolplanejava.service;

import com.github.xingjianluo.ephemeralenvcontrolplanejava.config.ControlPlaneProperties;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.error.BatchLimitExceededException;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.error.NotFoundException;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.model.ActionType;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.model.AuditRecord;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.model.Environment;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.model.EnvironmentStatus;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.model.Subject;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.repo.InMemoryAuditRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 审计服务。
 * - 每次状态变更调用 record 追加一条记录（触发主体、原因、前后状态）；
 * - 记录中绝不包含配置值/敏感值，日志同步打印“非敏感判定摘要”；
 * - 查询受租户隔离（requireTenant），按环境查询还要有 VIEW_ENV 权限；
 * - limit 受配置上限约束，超限拒绝（调用方应分页），不允许无界查询。
 */
@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    private final InMemoryAuditRepository auditRepository;
    private final AccessControlService accessControlService;
    private final ControlPlaneProperties properties;

    public AuditService(InMemoryAuditRepository auditRepository,
                        AccessControlService accessControlService,
                        ControlPlaneProperties properties) {
        this.auditRepository = auditRepository;
        this.accessControlService = accessControlService;
        this.properties = properties;
    }

    public void record(Subject actor, Environment env, ActionType action, String reason,
                       EnvironmentStatus before, EnvironmentStatus after) {
        AuditRecord rec = new AuditRecord(
                auditRepository.nextId(),
                env.getTenantId(),
                env.getId(),
                actor == null ? "system" : actor.name(),
                action,
                reason == null ? "" : reason,
                before,
                after,
                System.currentTimeMillis());
        auditRepository.append(rec);
        // 日志仅输出非敏感元数据（输入主体 + 判定前后状态），不含任何配置与密钥。
        log.info("audit actor={} tenant={} env={} action={} {} -> {} reason='{}'",
                rec.actor(), rec.tenantId(), rec.envId(), rec.action(),
                rec.before(), rec.after(), rec.reason());
    }

    /** 系统动作（扫描/收敛）也产生审计：actor 记为 system。 */
    public void recordSystem(Environment env, ActionType action, String reason,
                             EnvironmentStatus before, EnvironmentStatus after) {
        record(null, env, action, reason, before, after);
    }

    public List<AuditRecord> query(Subject actor, String tenantId, String envId, int limit) {
        if (limit <= 0) {
            throw new BatchLimitExceededException("limit must be positive");
        }
        if (limit > properties.getListMaxLimit()) {
            throw new BatchLimitExceededException(
                    "limit " + limit + " exceeds configured max " + properties.getListMaxLimit());
        }
        accessControlService.requireTenant(actor, tenantId);
        if (envId != null) {
            // envId 对调用方是环境名（API 不暴露内部 id），先鉴权该环境再查询。
            // 这里 envId 参数实际上传环境名时由控制器解析；保持仓库接口按内部 id 查询，
            // 控制器负责把名称解析为环境对象并完成 VIEW_ENV 鉴权。
            throw new UnsupportedOperationException("use queryForEnv with resolved environment");
        }
        return auditRepository.findByTenant(tenantId, limit);
    }

    /** 针对具体环境的审计查询：先解析+VIEW_ENV 鉴权，不存在抛 NOT_FOUND。 */
    public List<AuditRecord> queryForEnv(Subject actor, Environment env, int limit) {
        if (limit <= 0) {
            throw new BatchLimitExceededException("limit must be positive");
        }
        if (limit > properties.getListMaxLimit()) {
            throw new BatchLimitExceededException(
                    "limit " + limit + " exceeds configured max " + properties.getListMaxLimit());
        }
        accessControlService.require(actor, env, Permission.VIEW_ENV);
        List<AuditRecord> out = auditRepository.findByEnv(env.getTenantId(), env.getId(), limit);
        if (out.isEmpty()) {
            throw new NotFoundException("no audit records for environment: " + env.getName());
        }
        return out;
    }
}
