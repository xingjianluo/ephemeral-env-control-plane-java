package com.github.xingjianluo.ephemeralenvcontrolplanejava.service;

import com.github.xingjianluo.ephemeralenvcontrolplanejava.model.AuditRecord;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.model.EnvAction;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.model.EnvStatus;

import java.util.List;

/** Writes and queries audit records. Never receives secret values. */
public interface AuditService {

    void record(String tenantId, String envId, String actor, EnvAction action,
                EnvStatus from, EnvStatus to, String reason);

    List<AuditRecord> query(String tenantId, String envId, int offset, int limit);

    int count(String tenantId, String envId);
}
