package com.github.xingjianluo.ephemeralenvcontrolplanejava.tests;

import com.github.xingjianluo.ephemeralenvcontrolplanejava.error.ApiException;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.error.ErrorCode;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.model.Environment;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.web.EnvViewMapper;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.web.dto.EnvView;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.*;

class AccessControlTest {

    private static final Logger log = LoggerFactory.getLogger(AccessControlTest.class);
    private final TestHarness h = new TestHarness();

    @Test
    void viewerCanViewButNotSecretsRenewOrRecycle() {
        Environment env = h.fresh(h.alice(), "guarded");

        // Viewer sees non-sensitive data ...
        Environment viewed = h.envs.get("carol", "alpha", env.id(), false);
        assertEquals(env.id(), viewed.id());
        EnvView safeView = EnvViewMapper.toView(viewed, false);
        assertTrue(safeView.secrets().isEmpty(),
                "no secrets without explicit authorized request");

        // ... but secrets are refused.
        ApiException secretEx = assertThrows(ApiException.class,
                () -> h.envs.get("carol", "alpha", env.id(), true));
        assertEquals(ErrorCode.FORBIDDEN, secretEx.code());
        log.info("input viewer secrets request; decision code={} (generic, no values)",
                secretEx.code());

        ApiException renewEx = assertThrows(ApiException.class,
                () -> h.envs.renew("carol", "alpha", env.id(), 3600L));
        assertEquals(ErrorCode.FORBIDDEN, renewEx.code());

        ApiException delEx = assertThrows(ApiException.class,
                () -> h.envs.recycle("carol", "alpha", env.id(), "intruder"));
        assertEquals(ErrorCode.FORBIDDEN, delEx.code());
        log.info("input viewer renew/recycle; decision FORBIDDEN; env still READY={}",
                h.envRepo.findById(env.id()).orElseThrow().status());
    }

    @Test
    void ownerCanReadSecretsAndMemberCanMutateOwnEnv() {
        Environment env = h.fresh(h.alice(), "owned");
        Environment withSecrets = h.envs.get("alice", "alpha", env.id(), true);
        EnvView secretView = EnvViewMapper.toView(withSecrets, true);
        assertFalse(secretView.secrets().isEmpty());
        assertNotNull(secretView.secrets().get("api_key"));
        log.info("input owner secrets request; decision allowed, key present, never logged");

        // bob creates his own env and may renew/recycle it even as MEMBER.
        Environment bobs = h.fresh(h.bob(), "bobs-env");
        assertDoesNotThrow(() -> h.envs.renew("bob", "alpha", bobs.id(), 7200L));
        // but bob cannot touch alice's env.
        ApiException ex = assertThrows(ApiException.class,
                () -> h.envs.renew("bob", "alpha", env.id(), 1200L));
        assertEquals(ErrorCode.FORBIDDEN, ex.code());
        log.info("input member renews other-owner env; decision FORBIDDEN");
    }

    @Test
    void crossTenantAccessIsForbiddenAndLeakFree() {
        Environment env = h.fresh(h.alice(), "alpha-secret");

        // dave (beta) must not learn that the env exists: generic 404 vs 403
        // choice must never include env internals. Here lookup is by id in beta.
        ApiException getEx = assertThrows(ApiException.class,
                () -> h.envs.get("dave", "beta", env.id(), true));
        log.info("input cross-tenant read envId={} as dave/beta; decision code={} msg='{}'",
                env.id(), getEx.code(), getEx.getMessage());
        assertTrue(getEx.code() == ErrorCode.ENV_NOT_FOUND
                        || getEx.code() == ErrorCode.FORBIDDEN,
                "cross-tenant access must be rejected");
        assertNull(getEx.getCause(), "no internal details leaked");

        assertThrows(ApiException.class,
                () -> h.envs.recycle("dave", "beta", env.id(), "cross"));
    }

    @Test
    void missingPrincipalIsUnauthenticated() {
        ApiException ex = assertThrows(ApiException.class,
                () -> h.envs.get(null, "alpha", "whatever", false));
        assertEquals(ErrorCode.UNAUTHENTICATED, ex.code());
        log.info("input no principal; decision code={}", ex.code());
    }

    private static void assertNotNull(Object o) {
        org.junit.jupiter.api.Assertions.assertNotNull(o);
    }
}
