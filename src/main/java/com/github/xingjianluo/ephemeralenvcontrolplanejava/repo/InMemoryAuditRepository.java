package com.github.xingjianluo.ephemeralenvcontrolplanejava.repo;

import com.github.xingjianluo.ephemeralenvcontrolplanejava.model.AuditRecord;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 内存审计仓库：追加写，单调递增 id；查询倒序返回并强制 limit 上限，避免无界结果。
 * 为支持本地长期运行，追加列表带容量硬上限（超出丢弃最旧记录，绝不无界占用内存）。
 */
@Repository
public class InMemoryAuditRepository implements AuditRepository {

    static final int HARD_CAP = 10_000;

    private final CopyOnWriteArrayList<AuditRecord> records = new CopyOnWriteArrayList<>();
    private final AtomicLong idGen = new AtomicLong(1);

    @Override
    public void append(AuditRecord record) {
        records.add(record);
        while (records.size() > HARD_CAP) {
            records.remove(0);
        }
    }

    public long nextId() {
        return idGen.getAndIncrement();
    }

    @Override
    public List<AuditRecord> findByEnv(String tenantId, String envId, int limit) {
        if (limit <= 0) {
            return List.of();
        }
        List<AuditRecord> out = new ArrayList<>();
        for (AuditRecord r : records) {
            if (r.tenantId().equals(tenantId) && r.envId().equals(envId)) {
                out.add(r);
            }
        }
        out.sort(Comparator.comparingLong(AuditRecord::id).reversed());
        return out.size() > limit ? out.subList(0, limit) : out;
    }

    @Override
    public List<AuditRecord> findByTenant(String tenantId, int limit) {
        if (limit <= 0) {
            return List.of();
        }
        List<AuditRecord> out = new ArrayList<>();
        for (AuditRecord r : records) {
            if (r.tenantId().equals(tenantId)) {
                out.add(r);
            }
        }
        out.sort(Comparator.comparingLong(AuditRecord::id).reversed());
        return out.size() > limit ? out.subList(0, limit) : out;
    }
}
