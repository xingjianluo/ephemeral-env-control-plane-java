package com.github.xingjianluo.ephemeralenvcontrolplanejava.service;

import com.github.xingjianluo.ephemeralenvcontrolplanejava.config.TimeSource;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.model.AuditRecord;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.model.EnvAction;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.model.EnvStatus;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.repo.AuditRepository;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Audit facade. Entries intentionally carry only actor, action, reason and the
 * from/to states — there is no parameter that could convey secret values.
 */
public class DefaultAuditService implements AuditService {

    private final AuditRepository auditRepository;
    private final TimeSource timeSource;
    private final AtomicLong seq = new AtomicLong();

    public DefaultAuditService(AuditRepository auditRepository, TimeSource timeSource) {
        this.auditRepository = auditRepository;
        this.timeSource = timeSource;
    }

    @Override
    public void record(String tenantId, String envId, String actor, EnvAction action,
                       EnvStatus from, EnvStatus to, String reason) {
        String id = "aud-" + seq.incrementAndGet() + "-"
                + UUID.randomUUID().toString().substring(0, 8);
        AuditRecord record = new AuditRecord(id, timeSource.now(), tenantId, envId,
                actor, action, from, to, safeReason(reason));
        auditRepository.append(record);
    }

    /** Truncate and keep reasons free of accidental newlines/secrets payloads. */
    private static String safeReason(String reason) {
        if (reason == null) {
            return "";
        }
        String r = reason.replaceAll("\\s+", " ").trim();
        return r.length() > 200 ? r.substring(0, 200) : r;
    }

    @Override
    public List<AuditRecord> query(String tenantId, String envId, int offset, int limit) {
        return auditRepository.query(tenantId, envId, offset, limit);
    }

    @Override
    public int count(String tenantId, String envId) {
        return auditRepository.count(tenantId, envId);
    }
}
