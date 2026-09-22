package com.github.xingjianluo.ephemeralenvcontrolplanejava.repo;

import com.github.xingjianluo.ephemeralenvcontrolplanejava.model.Quota;
import org.springframework.stereotype.Repository;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** 内存配额仓库。同一租户返回同一个 Quota 实例（更新在其自身上发生并被租户锁保护）。 */
@Repository
public class InMemoryQuotaRepository implements QuotaRepository {

    private final ConcurrentMap<String, Quota> store = new ConcurrentHashMap<>();

    @Override
    public Quota getOrCreate(String tenantId) {
        return store.computeIfAbsent(tenantId, k -> new Quota());
    }

    @Override
    public void save(String tenantId, Quota quota) {
        store.put(tenantId, quota);
    }
}
