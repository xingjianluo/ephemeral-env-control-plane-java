package com.github.xingjianluo.ephemeralenvcontrolplanejava.web;

import com.github.xingjianluo.ephemeralenvcontrolplanejava.model.Environment;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.model.ResourceHandle;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.web.dto.EnvView;

import java.util.List;
import java.util.Map;

/**
 * Builds the API projection of an environment. Secret values are included only
 * when {@code revealSecrets} is true (access control checked beforehand);
 * otherwise the map is omitted entirely.
 */
public final class EnvViewMapper {

    private EnvViewMapper() {
    }

    public static EnvView toView(Environment env, boolean revealSecrets) {
        List<String> resourceIds = env.resources().stream()
                .map(ResourceHandle::resourceId)
                .toList();
        Map<String, String> secrets = revealSecrets
                ? env.sensitiveConfig().secrets()
                : Map.of();
        return new EnvView(
                env.id(),
                env.tenantId(),
                env.name(),
                env.owner(),
                env.status().name(),
                env.templateId(),
                env.templateVersion(),
                env.resourceUnits(),
                env.configVersion(),
                env.lease().acquiredAt(),
                env.lease().expiresAt(),
                env.failureReason(),
                resourceIds,
                Map.of(), // non-sensitive rendered variables retained server-side
                secrets);
    }
}
