package com.github.xingjianluo.ephemeralenvcontrolplanejava.repo;

import com.github.xingjianluo.ephemeralenvcontrolplanejava.model.Quota;

/** 租户配额存储。 */
public interface QuotaRepository {

    Quota getOrCreate(String tenantId);

    void save(String tenantId, Quota quota);
}
