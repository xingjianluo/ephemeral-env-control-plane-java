package com.github.xingjianluo.ephemeralenvcontrolplanejava.repo;

import com.github.xingjianluo.ephemeralenvcontrolplanejava.model.AuditRecord;

import java.util.List;

/** 审计记录存储：追加写，按租户/环境倒序查询，查询带规模上限。 */
public interface AuditRepository {

    void append(AuditRecord record);

    List<AuditRecord> findByEnv(String tenantId, String envId, int limit);

    List<AuditRecord> findByTenant(String tenantId, int limit);
}
