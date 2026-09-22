package com.github.xingjianluo.ephemeralenvcontrolplanejava.model;

import com.github.xingjianluo.ephemeralenvcontrolplanejava.error.InvalidStateException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 环境聚合根。所有写操作必须在 EnvironmentService 的临界区内进行。
 * 合法状态转换：
 * PENDING -> PROVISIONING -> READY | FAILED
 * READY|FAILED -> RELEASING -> RELEASED
 * 任何跳变（例如 FAILED -> READY、RELEASED -> 其他）都会被拒绝。
 */
public class Environment {

    private final String id;
    private final String tenantId;
    private final String name;
    private final String owner;
    private final long createdAtMillis;
    private final int totalCpu;
    private final int totalMemoryMb;
    private final List<ResourceSpec> specs;

    private String templateVersion;
    private EnvironmentStatus status;
    private long expiresAtMillis;
    private long updatedAtMillis;
    private String failureReason;
    private List<String> resourceIds;
    private Map<String, Object> configValues;
    private String secretValue;
    private boolean quotaReleased;

    private Environment(String id, String tenantId, String name, String owner,
                        String templateVersion, long now, long ttlMillis,
                        List<ResourceSpec> specs) {
        this.id = id;
        this.tenantId = tenantId;
        this.name = name;
        this.owner = owner;
        this.templateVersion = templateVersion;
        this.createdAtMillis = now;
        this.updatedAtMillis = now;
        this.expiresAtMillis = now + ttlMillis;
        this.specs = List.copyOf(specs);
        this.totalCpu = specs.stream().mapToInt(ResourceSpec::cpu).sum();
        this.totalMemoryMb = specs.stream().mapToInt(ResourceSpec::memoryMb).sum();
        this.status = EnvironmentStatus.PENDING;
        this.resourceIds = new ArrayList<>();
        this.quotaReleased = false;
    }

    /** 工厂：创建一个 PENDING 环境（尚未分配任何后端资源）。 */
    public static Environment createPending(String id, String tenantId, String name, String owner,
                                            String templateVersion, long now, long ttlMillis,
                                            List<ResourceSpec> specs) {
        return new Environment(id, tenantId, name, owner, templateVersion, now, ttlMillis, specs);
    }

    /** 严格状态转换，拒绝非法跳变。 */
    public void transitionTo(EnvironmentStatus target) {
        if (!canTransition(this.status, target)) {
            throw new InvalidStateException(
                    "illegal state transition: " + this.status + " -> " + target
                            + " (env=" + name + ", tenant=" + tenantId + ")");
        }
        this.status = target;
        this.updatedAtMillis = System.currentTimeMillis();
    }

    public static boolean canTransition(EnvironmentStatus from, EnvironmentStatus to) {
        return switch (from) {
            case PENDING -> to == EnvironmentStatus.PROVISIONING;
            case PROVISIONING -> to == EnvironmentStatus.READY || to == EnvironmentStatus.FAILED;
            case READY, FAILED -> to == EnvironmentStatus.RELEASING;
            case RELEASING -> to == EnvironmentStatus.RELEASED || to == EnvironmentStatus.FAILED;
            case RELEASED -> false;
        };
    }

    public boolean isExpired(long now) {
        return status == EnvironmentStatus.READY && now >= expiresAtMillis;
    }

    /** 续期：仅 READY 环境可续，基于“当前时间”重新计算到期，返回是否续期成功。 */
    public long renew(long now, long ttlMillis) {
        if (status != EnvironmentStatus.READY) {
            throw new InvalidStateException(
                    "only READY environment can be renewed, current=" + status + " (env=" + name + ")");
        }
        this.expiresAtMillis = now + ttlMillis;
        this.updatedAtMillis = now;
        return this.expiresAtMillis;
    }

    public boolean isFinal() {
        return status == EnvironmentStatus.RELEASED;
    }

    public String getId() { return id; }
    public String getTenantId() { return tenantId; }
    public String getName() { return name; }
    public String getOwner() { return owner; }
    public String getTemplateVersion() { return templateVersion; }
    public EnvironmentStatus getStatus() { return status; }
    public long getCreatedAtMillis() { return createdAtMillis; }
    public long getExpiresAtMillis() { return expiresAtMillis; }
    public long getUpdatedAtMillis() { return updatedAtMillis; }
    public int getTotalCpu() { return totalCpu; }
    public int getTotalMemoryMb() { return totalMemoryMb; }
    public String getFailureReason() { return failureReason; }
    public List<ResourceSpec> getSpecs() { return specs; }
    public List<String> getResourceIds() { return resourceIds; }
    public Map<String, Object> getConfigValues() { return configValues; }
    public String getSecretValue() { return secretValue; }
    public boolean isQuotaReleased() { return quotaReleased; }

    public void setTemplateVersion(String templateVersion) { this.templateVersion = templateVersion; }
    public void setExpiresAtMillis(long expiresAtMillis) { this.expiresAtMillis = expiresAtMillis; }
    public void setFailureReason(String failureReason) { this.failureReason = failureReason; }
    public void setResourceIds(List<String> resourceIds) { this.resourceIds = resourceIds; }
    public void setConfigValues(Map<String, Object> configValues) { this.configValues = configValues; }
    public void setSecretValue(String secretValue) { this.secretValue = secretValue; }
    public void setQuotaReleased(boolean quotaReleased) { this.quotaReleased = quotaReleased; }
}
