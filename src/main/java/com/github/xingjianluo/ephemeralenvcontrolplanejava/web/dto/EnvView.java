package com.github.xingjianluo.ephemeralenvcontrolplanejava.web.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Safe projection of an environment. Secret values are either fully omitted
 * or replaced by "***REDACTED***"; never serialized raw.
 */
public record EnvView(
        String id,
        String tenantId,
        String name,
        String owner,
        String status,
        String templateId,
        String templateVersion,
        int resourceUnits,
        int configVersion,
        Instant acquiredAt,
        Instant expiresAt,
        String failureReason,
        List<String> resourceIds,
        Map<String, String> variables,
        Map<String, String> secrets) {
}
