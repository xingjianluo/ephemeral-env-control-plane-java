package com.github.xingjianluo.ephemeralenvcontrolplanejava.service;

import com.github.xingjianluo.ephemeralenvcontrolplanejava.error.ApiException;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.error.ErrorCode;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.model.Environment;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.model.QuotaUsage;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.model.TenantQuota;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Quota ledger. Each tenant has its own monitor, so reserve/check/release for a
 * tenant are atomic and quota races cannot over-allocate. Counters can be
 * rebuilt from the authoritative environment set after a restart.
 */
public class DefaultQuotaService implements QuotaService {

    private final Map<String, TenantQuota> quotas = new ConcurrentHashMap<>();
    private final Map<String, Object> tenantLocks = new ConcurrentHashMap<>();
    // [0] = active env count, [1] = used resource units
    private final Map<String, int[]> usage = new ConcurrentHashMap<>();

    private Object lockOf(String tenantId) {
        return tenantLocks.computeIfAbsent(tenantId, k -> new Object());
    }

    private int[] counters(String tenantId) {
        return usage.computeIfAbsent(tenantId, k -> new int[2]);
    }

    @Override
    public TenantQuota getQuota(String tenantId) {
        TenantQuota q = quotas.get(tenantId);
        if (q == null) {
            throw new ApiException(ErrorCode.TENANT_NOT_FOUND,
                    "unknown tenant: " + tenantId);
        }
        return q;
    }

    @Override
    public QuotaUsage usage(String tenantId) {
        TenantQuota q = getQuota(tenantId);
        int[] c;
        synchronized (lockOf(tenantId)) {
            c = counters(tenantId).clone();
        }
        return new QuotaUsage(tenantId, c[0], c[1], q.maxActiveEnvs(), q.maxResourceUnits());
    }

    @Override
    public void reserve(String tenantId, int units) {
        TenantQuota q = getQuota(tenantId);
        synchronized (lockOf(tenantId)) {
            int[] c = counters(tenantId);
            if (c[0] + 1 > q.maxActiveEnvs()) {
                throw new ApiException(ErrorCode.QUOTA_EXCEEDED,
                        "quota exceeded: max active environments " + q.maxActiveEnvs()
                                + ", current " + c[0]);
            }
            if (c[1] + units > q.maxResourceUnits()) {
                throw new ApiException(ErrorCode.QUOTA_EXCEEDED,
                        "quota exceeded: max resource units " + q.maxResourceUnits()
                                + ", requested additional " + units + ", current " + c[1]);
            }
            c[0]++;
            c[1] += units;
        }
    }

    @Override
    public void release(String tenantId, int units) {
        TenantQuota q = quotas.get(tenantId);
        if (q == null) {
            // Tenant config gone (never seeded) — nothing safe to mutate.
            return;
        }
        synchronized (lockOf(tenantId)) {
            int[] c = counters(tenantId);
            c[0] = Math.max(0, c[0] - 1);
            c[1] = Math.max(0, c[1] - units);
        }
    }

    @Override
    public void recompute(Collection<Environment> envs) {
        // Only environments that still hold resources/quota count.
        Map<String, int[]> fresh = new ConcurrentHashMap<>();
        for (Environment e : envs) {
            if (!e.status().holdsQuota()) {
                continue;
            }
            int[] c = fresh.computeIfAbsent(e.tenantId(), k -> new int[2]);
            c[0]++;
            c[1] += e.resourceUnits();
        }
        synchronized (this) {
            usage.clear();
            usage.putAll(fresh);
        }
    }

    @Override
    public void setQuota(TenantQuota quota) {
        quotas.put(quota.tenantId(), quota);
        counters(quota.tenantId());
    }
}
