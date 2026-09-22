package com.github.xingjianluo.ephemeralenvcontrolplanejava.template;

import java.util.Set;

/**
 * Immutable description of one version of a configuration template.
 * New versions add fields with stable defaults; removed/unknown fields are
 * rejected explicitly rather than ignored.
 */
public record TemplateDescriptor(
        String templateId,
        String version,
        long defaultTtlSeconds,
        int defaultResourceUnits,
        Set<String> supportedFields,
        Set<String> secretFields) {
}
