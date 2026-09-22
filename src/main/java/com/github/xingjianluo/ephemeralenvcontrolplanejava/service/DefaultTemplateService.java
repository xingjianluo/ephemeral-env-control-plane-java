package com.github.xingjianluo.ephemeralenvcontrolplanejava.service;

import com.github.xingjianluo.ephemeralenvcontrolplanejava.error.ApiException;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.error.ErrorCode;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.model.SensitiveConfig;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.template.RenderedTemplate;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.template.TemplateDescriptor;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Versioned template registry.
 *
 * <p>Compatibility policy:</p>
 * <ul>
 *   <li>Unknown versions are rejected with UNSUPPORTED_TEMPLATE_VERSION.</li>
 *   <li>Unknown request fields are rejected with UNSUPPORTED_TEMPLATE_FIELD
 *       (never silently ignored).</li>
 *   <li>Fields added by a newer version have a fixed default, so a request that
 *       was valid under v1 renders identically (semantically) under the v2
 *       default. v1 requests never gain the v2-only feature implicitly.</li>
 * </ul>
 */
public class DefaultTemplateService implements TemplateService {

    private final Map<String, TemplateDescriptor> descriptors = new ConcurrentHashMap<>();

    @Override
    public void register(TemplateDescriptor descriptor) {
        descriptors.put(key(descriptor.templateId(), descriptor.version()), descriptor);
    }

    private static String key(String templateId, String version) {
        return templateId + "#" + version;
    }

    @Override
    public TemplateDescriptor describe(String templateId, String version) {
        TemplateDescriptor d = descriptors.get(key(templateId, version));
        if (d == null) {
            throw new ApiException(ErrorCode.TEMPLATE_NOT_FOUND,
                    "template not found: " + templateId + "@" + version);
        }
        return d;
    }

    @Override
    public RenderedTemplate render(String templateId, String version,
                                   Map<String, String> parameters) {
        TemplateDescriptor d = describe(templateId, version);
        Map<String, String> in = parameters == null ? Map.of() : parameters;

        // Explicit rejection of anything the version does not understand.
        for (String field : in.keySet()) {
            if (!d.supportedFields().contains(field)) {
                throw new ApiException(ErrorCode.UNSUPPORTED_TEMPLATE_FIELD,
                        "field '" + field + "' is not supported by "
                                + templateId + "@" + version);
            }
        }

        Map<String, String> variables = new TreeMap<>();
        Map<String, String> secrets = new LinkedHashMap<>();

        // image: present since v1, stable default.
        String image = in.getOrDefault("image", "registry.local/default:1.0");
        validateImage(image, templateId, version);
        variables.put("image", image);

        // replicas: only v2. v1 requests can never express it; semantics stay
        // pinned to a single unit for v1.
        int replicas;
        int resourceUnits;
        if ("2".equals(version)) {
            String raw = in.get("replicas");
            replicas = raw == null ? 1 : parseReplicas(raw, templateId, version);
            variables.put("replicas", Integer.toString(replicas));
            resourceUnits = replicas;
        } else {
            replicas = 1;
            resourceUnits = d.defaultResourceUnits();
            variables.put("replicas", "1");
        }

        // api_key secret: present since v1 with a generated default.
        String apiKey = in.get("api_key");
        if (apiKey == null || apiKey.isBlank()) {
            apiKey = "sk-generated-" + UUID.randomUUID();
        }
        secrets.put("api_key", apiKey);

        return new RenderedTemplate(templateId, version, d.defaultTtlSeconds(),
                resourceUnits, Map.copyOf(variables),
                new SensitiveConfig(Map.copyOf(secrets)));
    }

    private static void validateImage(String image, String templateId, String version) {
        if (image.isBlank() || image.contains(" ")) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR,
                    "invalid 'image' for " + templateId + "@" + version);
        }
    }

    private static int parseReplicas(String raw, String templateId, String version) {
        try {
            int n = Integer.parseInt(raw);
            if (n < 1 || n > 10) {
                throw new NumberFormatException();
            }
            return n;
        } catch (NumberFormatException e) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR,
                    "'replicas' must be an integer between 1 and 10 for "
                            + templateId + "@" + version);
        }
    }
}
