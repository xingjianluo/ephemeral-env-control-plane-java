package com.github.xingjianluo.ephemeralenvcontrolplanejava.model;

/** Per-tenant quota limits. */
public record TenantQuota(String tenantId, int maxActiveEnvs, int maxResourceUnits) {
}
