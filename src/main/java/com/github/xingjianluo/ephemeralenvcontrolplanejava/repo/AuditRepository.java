package com.github.xingjianluo.ephemeralenvcontrolplanejava.repo;

import com.github.xingjianluo.ephemeralenvcontrolplanejava.model.AuditRecord;

import java.util.List;

/** Bounded, append-only audit store. */
public interface AuditRepository {

    void append(AuditRecord record);

    List<AuditRecord> query(String tenantId, String envId, int offset, int limit);

    int count(String tenantId, String envId);

    void reset();
}
