package com.github.xingjianluo.ephemeralenvcontrolplanejava.tests;

import com.github.xingjianluo.ephemeralenvcontrolplanejava.error.ApiException;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.error.ErrorCode;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.model.Environment;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.model.EnvStatus;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.model.QuotaUsage;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.service.ApplyCommand;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class QuotaCompetitionTest {

    private static final Logger log = LoggerFactory.getLogger(QuotaCompetitionTest.class);
    private final TestHarness h = new TestHarness();

    @Test
    void envCountQuotaIsEnforcedAndReturned() {
        h.fresh(h.alice(), "q-env-1");
        h.fresh(h.alice(), "q-env-2");
        h.fresh(h.alice(), "q-env-3");
        QuotaUsage full = h.quotas.usage("alpha");
        log.info("input alpha limit=3; decision activeEnvs={} units={}",
                full.activeEnvs(), full.usedUnits());
        assertEquals(3, full.activeEnvs());

        ApiException ex = assertThrows(ApiException.class,
                () -> h.fresh(h.alice(), "q-env-4"));
        assertEquals(ErrorCode.QUOTA_EXCEEDED, ex.code());
        log.info("input 4th env; decision REJECTED code={} reason='{}'",
                ex.code(), ex.getMessage());

        Environment env2 = h.envRepo.findById(h.envId("alpha", "q-env-2")).orElseThrow();
        h.envs.recycle("alice", "alpha", env2.id(), "cleanup");
        assertEquals(2, h.quotas.usage("alpha").activeEnvs());
        Environment replacement = h.fresh(h.alice(), "q-new");
        assertEquals(EnvStatus.READY, replacement.status());
        assertEquals(3, h.quotas.usage("alpha").activeEnvs());
        log.info("input recycle+reapply; decision activeEnvs=3, no ghost occupancy");
    }

    @Test
    void unitQuotaIsEnforcedPerTemplateUnits() {
        // replicas=3 consumes 3 units; alpha has 6.
        ApplyCommand big = new ApplyCommand(h.alice(), "big-1", "demo", "2",
                Map.of("replicas", "3"), 3600L);
        h.envs.apply(big);
        ApplyCommand secondBig = new ApplyCommand(h.alice(), "big-2", "demo", "2",
                Map.of("replicas", "4"), 3600L); // 3 + 4 = 7 > 6
        ApiException ex = assertThrows(ApiException.class, () -> h.envs.apply(secondBig));
        assertEquals(ErrorCode.QUOTA_EXCEEDED, ex.code());
        log.info("input units 3+4 vs limit 6; decision REJECTED code={}", ex.code());
        assertEquals(3, h.quotas.usage("alpha").usedUnits());
    }

    @Test
    void tenantsAreIsolatedAndUnknownTenantRejected() {
        h.fresh(h.alice(), "alpha-only");
        assertEquals(1, h.quotas.usage("alpha").activeEnvs());
        assertEquals(0, h.quotas.usage("beta").activeEnvs());

        ApiException ex = assertThrows(ApiException.class,
                () -> h.quotas.usage("gamma"));
        assertEquals(ErrorCode.TENANT_NOT_FOUND, ex.code());
        log.info("input unknown tenant gamma; decision code={}", ex.code());
    }

    @Test
    void concurrentAppliesNeverExceedQuota() throws Exception {
        // beta allows 2 envs; fire 12 concurrent applies.
        int threads = 12;
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        Thread[] pool = new Thread[threads];
        for (int i = 0; i < threads; i++) {
            final int idx = i;
            pool[i] = new Thread(() -> {
                try {
                    start.await();
                    h.envs.apply(new ApplyCommand(h.dave(), "race-" + idx,
                            "demo", "1", Map.of(), 3600L));
                    success.incrementAndGet();
                } catch (ApiException e) {
                    if (e.code() == ErrorCode.QUOTA_EXCEEDED) {
                        rejected.incrementAndGet();
                    }
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        for (Thread t : pool) t.start();
        start.countDown();
        for (Thread t : pool) t.join();

        QuotaUsage usage = h.quotas.usage("beta");
        log.info("input beta concurrent applies threads={}; decision success={} rejected={} activeEnvs={} units={}",
                threads, success.get(), rejected.get(), usage.activeEnvs(), usage.usedUnits());
        assertEquals(2, success.get());
        assertEquals(10, rejected.get());
        assertEquals(2, usage.activeEnvs());
        assertEquals(2, usage.usedUnits());
    }
}
