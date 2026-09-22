package com.github.xingjianluo.ephemeralenvcontrolplanejava.model;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Sensitive key/values attached to an environment. Values must never appear in
 * audits, logs or unauthorized API responses.
 */
public record SensitiveConfig(Map<String, String> secrets) {

    public SensitiveConfig {
        secrets = Map.copyOf(secrets);
    }

    public static SensitiveConfig empty() {
        return new SensitiveConfig(Map.of());
    }

    public Map<String, String> masked() {
        Map<String, String> m = new LinkedHashMap<>();
        secrets.forEach((k, v) -> m.put(k, "***REDACTED***"));
        return m;
    }
}
