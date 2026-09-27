package com.github.xingjianluo.ephemeralenvcontrolplanejava.web.dto;

import com.github.xingjianluo.ephemeralenvcontrolplanejava.model.Environment;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.model.EnvironmentStatus;

import java.util.Map;

/**
 * 环境对外视图（普通权限）。
 * 安全约束：
 * - 不含 secretValue（敏感值只能经 /secret 且经 VIEW_SENSITIVE_CONFIG 授权获取）；
 * - 不含内部资源 ID、资源规格细节、审计明细；
 * - RELEASED 环境仅暴露最小字段，避免泄露已销毁环境的内部细节；
 * - READY / SUSPENDED 携带非敏感配置快照（其中敏感字段已掩码为 ***）：
 *   挂起只让出计算资源，身份、归属与固化配置保持不变、仍可查询。
 */
public class EnvironmentResponse {

    private String id;
    private String tenantId;
    private String name;
    private String owner;
    private String templateVersion;
    private String status;
    private Long createdAtMillis;
    private Long expiresAtMillis;
    private Long suspendedAtMillis;
    private Integer totalCpu;
    private Integer totalMemoryMb;
    private String failureReason;
    private Map<String, Object> config;

    public static EnvironmentResponse of(Environment env) {
        EnvironmentResponse r = new EnvironmentResponse();
        r.name = env.getName();
        r.status = env.getStatus().name();
        if (env.getStatus() == EnvironmentStatus.RELEASED) {
            // 已回收：只给最小可辨识信息，不泄露任何内部细节。
            return r;
        }
        r.id = env.getId();
        r.tenantId = env.getTenantId();
        r.owner = env.getOwner();
        r.templateVersion = env.getTemplateVersion();
        r.createdAtMillis = env.getCreatedAtMillis();
        r.expiresAtMillis = env.getExpiresAtMillis();
        r.suspendedAtMillis = env.getStatus() == EnvironmentStatus.SUSPENDED
                ? env.getSuspendedAtMillis() : null;
        r.totalCpu = env.getTotalCpu();
        r.totalMemoryMb = env.getTotalMemoryMb();
        // 失败/挂起中/恢复中/回收中的中间态都暴露失败原因，便于查询与重试。
        r.failureReason = switch (env.getStatus()) {
            case FAILED, SUSPENDING, RESUMING, RELEASING -> env.getFailureReason();
            default -> null;
        };
        if (env.getStatus() == EnvironmentStatus.READY
                || env.getStatus() == EnvironmentStatus.SUSPENDED) {
            r.config = env.getConfigValues(); // 敏感字段已在固化时掩码
        }
        return r;
    }

    public String getId() { return id; }
    public String getTenantId() { return tenantId; }
    public String getName() { return name; }
    public String getOwner() { return owner; }
    public String getTemplateVersion() { return templateVersion; }
    public String getStatus() { return status; }
    public Long getCreatedAtMillis() { return createdAtMillis; }
    public Long getExpiresAtMillis() { return expiresAtMillis; }
    public Long getSuspendedAtMillis() { return suspendedAtMillis; }
    public Integer getTotalCpu() { return totalCpu; }
    public Integer getTotalMemoryMb() { return totalMemoryMb; }
    public String getFailureReason() { return failureReason; }
    public Map<String, Object> getConfig() { return config; }
}
