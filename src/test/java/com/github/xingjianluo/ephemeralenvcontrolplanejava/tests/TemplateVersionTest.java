package com.github.xingjianluo.ephemeralenvcontrolplanejava.tests;

import com.github.xingjianluo.ephemeralenvcontrolplanejava.error.ApiException;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.error.ErrorCode;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.model.Environment;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.model.EnvStatus;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.service.ApplyCommand;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.template.RenderedTemplate;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TemplateVersionTest {

    private static final Logger log = LoggerFactory.getLogger(TemplateVersionTest.class);
    private final TestHarness h = new TestHarness();

    @Test
    void v1SemanticsRemainStableWhenV2Exists() {
        RenderedTemplate v1 = h.templates.render("demo", "1", Map.of());
        RenderedTemplate v1Explicit = h.templates.render("demo", "1",
                Map.of("image", "img:1", "api_key", "k1"));
        RenderedTemplate v2Default = h.templates.render("demo", "2", Map.of());
        RenderedTemplate v2 = h.templates.render("demo", "2", Map.of("replicas", "3"));

        log.info("input render v1/v1-explicit/v2-default/v2 replicas; decision units={},{},{},{}",
                v1.resourceUnits(), v1Explicit.resourceUnits(),
                v2Default.resourceUnits(), v2.resourceUnits());
        assertEquals(1, v1.resourceUnits());
        assertEquals(1, v1Explicit.resourceUnits());
        assertEquals("img:1", v1Explicit.variables().get("image"));
        assertEquals(1, v2Default.resourceUnits(), "missing v2 field defaults to 1");
        assertEquals(3, v2.resourceUnits());
        assertNotNull(v1.sensitiveConfig().secrets().get("api_key"));
    }

    @Test
    void unsupportedVersionAndFieldsAreRejectedExplicitly() {
        ApiException badVersion = assertThrows(ApiException.class,
                () -> h.templates.render("demo", "9", Map.of()));
        assertEquals(ErrorCode.TEMPLATE_NOT_FOUND, badVersion.code());
        log.info("input version=9; decision code={} msg='{}'",
                badVersion.code(), badVersion.getMessage());

        ApiException badFieldV1 = assertThrows(ApiException.class,
                () -> h.templates.render("demo", "1", Map.of("replicas", "2")));
        assertEquals(ErrorCode.UNSUPPORTED_TEMPLATE_FIELD, badFieldV1.code());
        log.info("input v1 + replicas; decision code={} (not silently ignored)",
                badFieldV1.code());

        ApiException unknownField = assertThrows(ApiException.class,
                () -> h.templates.render("demo", "2", Map.of("gpu", "true")));
        assertEquals(ErrorCode.UNSUPPORTED_TEMPLATE_FIELD, unknownField.code());

        ApiException badReplicas = assertThrows(ApiException.class,
                () -> h.templates.render("demo", "2", Map.of("replicas", "99")));
        assertEquals(ErrorCode.VALIDATION_ERROR, badReplicas.code());
        log.info("input replicas=99; decision code={}", badReplicas.code());
    }

    @Test
    void existingReadyEnvIsNotRetroactivelyChangedByNewTemplateVersion() {
        // Create under v1 (1 unit). Registering/using v2 afterwards must not
        // mutate the already-ready environment's footprint or version.
        Environment before = h.fresh(h.alice(), "stable-env");
        assertEquals("1", before.templateVersion());
        assertEquals(1, before.resourceUnits());

        // Apply a different env with v2: the v1 env stays exactly as provisioned.
        h.envs.apply(new ApplyCommand(h.alice(), "new-style", "demo", "2",
                Map.of("replicas", "2"), 3600L));
        Environment after = h.envRepo.findById(before.id()).orElseThrow();

        log.info("input v1 env + later v2 env; decision oldVersion={} oldUnits={} (unchanged)",
                after.templateVersion(), after.resourceUnits());
        assertEquals(EnvStatus.READY, after.status());
        assertEquals("1", after.templateVersion());
        assertEquals(1, after.resourceUnits());
        assertEquals(1, after.resources().size());
    }
}
