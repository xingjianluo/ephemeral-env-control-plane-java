package com.github.xingjianluo.ephemeralenvcontrolplanejava.web.dto;

import java.time.Instant;

public record AuditView(
        String id,
        Instant at,
        String envId,
        String actor,
        String action,
        String fromStatus,
        String toStatus,
        String reason) {
}
