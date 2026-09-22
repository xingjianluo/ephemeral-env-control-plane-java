package com.github.xingjianluo.ephemeralenvcontrolplanejava.service;

import com.github.xingjianluo.ephemeralenvcontrolplanejava.model.QuotaUsage;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.model.TenantQuota;

/** Per-tenant quota reservation ledger. */
public interface QuotaService {

    TenantQuota getQuota(String tenantId);

    QuotaUsage usage(String tenantId);

    /** Atomically reserve an env slot + units; throws QUOTA_EXCEEDED on contention. */
    void reserve(String tenantId, int units);

    void release(String tenantId, int units);

    /** Rebuild counters from the authoritative environment set (restart recovery). */
    void recompute(java.util.Collection<com.github.xingjianluo.ephemeralenvcontrolplanejava.model.Environment> envs);

    void setQuota(TenantQuota quota);
}
