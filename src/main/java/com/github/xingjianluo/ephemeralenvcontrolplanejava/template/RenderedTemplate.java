package com.github.xingjianluo.ephemeralenvcontrolplanejava.template;

import com.github.xingjianluo.ephemeralenvcontrolplanejava.model.SensitiveConfig;

import java.util.Map;

/**
 * Result of rendering a template version for an application: the effective
 * lease, resource footprint, non-sensitive variables and secret values.
 */
public record RenderedTemplate(
        String templateId,
        String version,
        long ttlSeconds,
        int resourceUnits,
        Map<String, String> variables,
        SensitiveConfig sensitiveConfig) {
}
