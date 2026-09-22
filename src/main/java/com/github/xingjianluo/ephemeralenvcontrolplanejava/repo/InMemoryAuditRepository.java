package com.github.xingjianluo.ephemeralenvcontrolplanejava.repo;

import com.github.xingjianluo.ephemeralenvcontrolplanejava.model.AuditRecord;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Bounded append-only audit log. Oldest entries are evicted once the global
 * cap is reached so memory can never grow without limit. The deque keeps
 * newest records first.
 */
public class InMemoryAuditRepository implements AuditRepository {

    private final int maxRecords;
    private final ConcurrentLinkedDeque<AuditRecord> records = new ConcurrentLinkedDeque<>();

    public InMemoryAuditRepository() {
        this(2000);
    }

    public InMemoryAuditRepository(int maxRecords) {
        this.maxRecords = maxRecords;
    }

    @Override
    public synchronized void append(AuditRecord record) {
        records.addFirst(record);
        while (records.size() > maxRecords) {
            records.removeLast();
        }
    }

    @Override
    public List<AuditRecord> query(String tenantId, String envId, int offset, int limit) {
        if (offset < 0 || limit <= 0) {
            return List.of();
        }
        List<AuditRecord> out = new ArrayList<>();
        int skipped = 0;
        for (AuditRecord r : records) {
            if (!Objects.equals(r.tenantId(), tenantId)) {
                continue;
            }
            if (envId != null && !envId.equals(r.envId())) {
                continue;
            }
            if (skipped < offset) {
                skipped++;
                continue;
            }
            out.add(r);
            if (out.size() >= limit) {
                break;
            }
        }
        return List.copyOf(out);
    }

    @Override
    public int count(String tenantId, String envId) {
        int n = 0;
        for (AuditRecord r : records) {
            if (Objects.equals(r.tenantId(), tenantId)
                    && (envId == null || envId.equals(r.envId()))) {
                n++;
            }
        }
        return n;
    }

    @Override
    public synchronized void reset() {
        records.clear();
    }
}
