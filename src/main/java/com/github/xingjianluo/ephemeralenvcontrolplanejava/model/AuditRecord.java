package com.github.xingjianluo.ephemeralenvcontrolplanejava.model;

/** 一条审计记录：谁、因为什么、把环境从什么状态变到什么状态。不含任何敏感值。 */
public record AuditRecord(
        long id,
        String tenantId,
        String envId,
        String actor,
        ActionType action,
        String reason,
        EnvironmentStatus before,
        EnvironmentStatus after,
        long millis
) {
}
