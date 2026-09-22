package com.github.xingjianluo.ephemeralenvcontrolplanejava.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Mutable environment aggregate. All mutation happens under the environment's
 * per-key lock inside the service layer; state transitions themselves are
 * validated by {@link EnvStateMachine}.
 */
public final class Environment {

    private final String id;
    private final String tenantId;
    private final String name;
    private final String owner;
    private final String templateId;
    private final String templateVersion;
    private final int resourceUnits;

    private EnvStatus status;
    private Lease lease;
    private String failureReason;
    private int configVersion;
    private final List<ResourceHandle> resources = new ArrayList<>();
    private SensitiveConfig sensitiveConfig = SensitiveConfig.empty();
    private final Instant createdAt;
    private Instant updatedAt;

    public Environment(String id, String tenantId, String name, String owner,
                       String templateId, String templateVersion, int resourceUnits,
                       EnvStatus status, Lease lease, Instant createdAt) {
        this.id = id;
        this.tenantId = tenantId;
        this.name = name;
        this.owner = owner;
        this.templateId = templateId;
        this.templateVersion = templateVersion;
        this.resourceUnits = resourceUnits;
        this.status = status;
        this.lease = lease;
        this.createdAt = createdAt;
        this.updatedAt = createdAt;
        this.configVersion = 1;
    }

    public String id() { return id; }
    public String tenantId() { return tenantId; }
    public String name() { return name; }
    public String owner() { return owner; }
    public String templateId() { return templateId; }
    public String templateVersion() { return templateVersion; }
    public int resourceUnits() { return resourceUnits; }
    public EnvStatus status() { return status; }
    public Lease lease() { return lease; }
    public String failureReason() { return failureReason; }
    public int configVersion() { return configVersion; }
    public List<ResourceHandle> resources() { return List.copyOf(resources); }
    public SensitiveConfig sensitiveConfig() { return sensitiveConfig; }
    public Instant createdAt() { return createdAt; }
    public Instant updatedAt() { return updatedAt; }

    // --- mutators, used by services under the per-key lock ---

    public void transition(EnvStatus next, Instant now) {
        this.status = next;
        this.updatedAt = now;
    }

    public void renewLease(Instant newExpiry, Instant now) {
        this.lease = lease.withExpiry(newExpiry);
        this.updatedAt = now;
    }

    public void fail(String reason, Instant now) {
        this.status = EnvStatus.FAILED;
        this.failureReason = reason;
        this.updatedAt = now;
    }

    public void addResource(ResourceHandle handle) {
        this.resources.add(handle);
    }

    public void clearResources() {
        this.resources.clear();
    }

    public void attachSensitiveConfig(SensitiveConfig config) {
        this.sensitiveConfig = config;
    }

    public void bumpConfigVersion() {
        this.configVersion++;
    }
}
