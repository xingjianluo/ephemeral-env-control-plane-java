package com.github.xingjianluo.ephemeralenvcontrolplanejava.tests;

import com.github.xingjianluo.ephemeralenvcontrolplanejava.error.ApiException;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.error.ErrorCode;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.model.Environment;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.model.EnvStatus;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.service.ApplyCommand;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.service.ApplyResult;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class LifecycleIdempotencyTest {

    private static final Logger log = LoggerFactory.getLogger(LifecycleIdempotencyTest.class);
    private final TestHarness h = new TestHarness();

    @Test
    void duplicateApplyReturnsSameEnvironment() {
        ApplyCommand cmd = h.apply(h.alice(), "shop-web");
        ApplyResult first = h.envs.apply(cmd);
        ApplyResult second = h.envs.apply(cmd);

        log.info("input duplicate-apply tenant=alpha name=shop-web -> firstCreated={} secondCreated={} idSame={}",
                first.created(), second.created(),
                first.environment().id().equals(second.environment().id()));

        assertTrue(first.created());
        assertFalse(second.created(), "repeat apply must be idempotent, not a new env");
        assertEquals(first.environment().id(), second.environment().id());
        assertEquals(EnvStatus.READY, second.environment().status());
    }

    @Test
    void concurrentDuplicateAppliesCreateExactlyOne() throws Exception {
        AtomicInteger created = new AtomicInteger();
        int threads = 16;
        Thread[] pool = new Thread[threads];
        for (int i = 0; i < threads; i++) {
            pool[i] = new Thread(() -> {
                ApplyResult r = h.envs.apply(h.apply(h.alice(), "race-env"));
                if (r.created()) {
                    created.incrementAndGet();
                }
            });
        }
        for (Thread t : pool) t.start();
        for (Thread t : pool) t.join();

        log.info("input concurrent applies threads={} name=race-env; decision: createdCount={}",
                threads, created.get());
        assertEquals(1, created.get(), "exactly one env may be created under contention");
        assertEquals(1, h.quotas.usage("alpha").activeEnvs());
    }

    @Test
    void failedProvisionIsNeverReadyAndStaysQueryable() {
        String id = h.envId("alpha", "broken-env");
        h.backend.failAllocationAt(id, 0);
        ApplyResult result = h.envs.apply(h.apply(h.alice(), "broken-env"));
        Environment env = result.environment();

        log.info("input failing backend unit=0 name=broken-env; decision status={} reason='{}'",
                env.status(), env.failureReason());
        assertEquals(EnvStatus.FAILED, env.status());
        assertNotNull(env.failureReason(), "failure must leave a diagnostic reason");
        assertTrue(h.backend.listForEnv(id).isEmpty(),
                "partial allocations must be rolled back");
        assertEquals(0, h.quotas.usage("alpha").usedUnits(),
                "failed env must not hold quota");
    }

    @Test
    void recycleIsIdempotentAndNameCanBeReapplied() {
        Environment env = h.fresh(h.alice(), "short-lived");
        assertEquals(1, h.quotas.usage("alpha").activeEnvs());

        h.envs.recycle("alice", "alpha", env.id(), "done");
        h.envs.recycle("alice", "alpha", env.id(), "again"); // no error
        h.envs.recycle("alice", "alpha", env.id(), "again"); // no error

        log.info("input triple recycle; decision: quotaAfter={} backendResources={}",
                h.quotas.usage("alpha").activeEnvs(), h.backend.listAll().size());
        assertEquals(0, h.quotas.usage("alpha").activeEnvs());
        assertTrue(h.backend.listAll().isEmpty());

        ApplyResult again = h.envs.apply(h.apply(h.alice(), "short-lived"));
        assertTrue(again.created());
        assertEquals(EnvStatus.READY, again.environment().status());
    }

    @Test
    void invalidNamesAreRejected() {
        for (String bad : List.of("UPPER", "a", "with space", "-lead", "ok_trail_")) {
            ApiException ex = assertThrows(ApiException.class,
                    () -> h.envs.apply(new ApplyCommand(h.alice(), bad, "demo", "1",
                            Map.of(), 3600L)));
            assertEquals(ErrorCode.VALIDATION_ERROR, ex.code());
            log.info("input invalidName='{}'; decision: {} ({})",
                    bad, ex.code(), ex.getMessage());
        }
    }

}
