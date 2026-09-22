package com.github.xingjianluo.ephemeralenvcontrolplanejava.model;

import java.time.Instant;

/**
 * Immutable audit entry. Must never contain sensitive configuration values:
 * only actor, reason and state transitions are stored.
 */
public record AuditRecord(
        String id,
        Instant at,
        String tenantId,
        String envId,
        String actor,
        EnvAction action,
        EnvStatus fromStatus,
        EnvStatus toStatus,
        String reason) {
}
