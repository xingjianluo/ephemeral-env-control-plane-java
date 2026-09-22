package com.github.xingjianluo.ephemeralenvcontrolplanejava.tests;

import com.github.xingjianluo.ephemeralenvcontrolplanejava.error.ApiException;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.error.ErrorCode;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.model.Environment;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.model.EnvStatus;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.model.Subject;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.service.ApplyCommand;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class LeaseConcurrencyTest {

    private static final Logger log = LoggerFactory.getLogger(LeaseConcurrencyTest.class);
    private final TestHarness h = new TestHarness();

    @Test
    void expiredEnvCannotBeRenewedAndIsReapedOnce() {
        Environment env = h.fresh(h.alice(), "expiring");
        h.clock.advanceSeconds(3601); // ttl 3600 expired

        ApiException ex = assertThrows(ApiException.class,
                () -> h.envs.renew("alice", "alpha", env.id(), 3600L));
        assertEquals(ErrorCode.EXPIRED, ex.code());
        log.info("input renew at t+3601 ttl=3600; decision code={} (no half-renewal)",
                ex.code());

        int recycled = h.reaper.sweepOnce();
        assertEquals(1, recycled);
        assertTrue(h.envRepo.findById(env.id()).isEmpty(),
                "reaped env removed so name can be reapplied");
        assertTrue(h.backend.listAll().isEmpty());
        assertEquals(0, h.quotas.usage("alpha").activeEnvs());

        // Second sweep is a no-op; no double release.
        int again = h.reaper.sweepOnce();
        assertEquals(0, again);
        log.info("input second sweep; decision recycled=0, double-free absent");
    }

    @Test
    void reaperNeverTouchesEnvironmentsStillInLease() {
        Environment keep = h.fresh(h.alice(), "fresh-one");
        Environment soon = h.envs.apply(new ApplyCommand(
                h.alice(), "soon-gone", "demo", "1", null, 100L)).environment();
        h.clock.advanceSeconds(3599); // keep valid (1s left), soon long expired
        int recycled = h.reaper.sweepOnce();
        log.info("input sweep t+3599 ttl=3600; decision recycled={} (only expired)", recycled);
        assertEquals(1, recycled);
        assertEquals(EnvStatus.READY,
                h.envRepo.findById(keep.id()).orElseThrow().status());
        assertEquals(0, h.quotas.usage("alpha").activeEnvs() - 1);
    }

    @Test
    void concurrentRenewAndSweepAtExpiryNeverSettleHalfUpdated() throws Exception {
        // 24 rounds at the exact expiry instant: renewal and the expiry sweep
        // race. At t == expiresAt the lease is expired by contract, so the
        // deterministic result must be: renewal rejected AND env recycled,
        // exactly once — never a READY row with stale lease or double release.
        int rounds = 24;
        for (int r = 0; r < rounds; r++) {
            final Subject alice = h.alice();
            Environment env = h.envs.apply(new ApplyCommand(
                    alice, "edge-" + r, "demo", "1", null, 3600L)).environment();
            h.clock.advanceSeconds(3600); // exactly at the boundary

            CountDownLatch start = new CountDownLatch(1);
            AtomicInteger renewOk = new AtomicInteger();
            AtomicInteger renewRejected = new AtomicInteger();
            Thread renewer = new Thread(() -> {
                try {
                    start.await();
                    h.envs.renew("alice", "alpha", env.id(), 3600L);
                    renewOk.set(1);
                } catch (ApiException e) {
                    // Deterministic loss: expired ourselves, or the sweep won the
                    // lock first and the env is already gone (ENV_NOT_FOUND).
                    if (e.code() == ErrorCode.EXPIRED
                            || e.code() == ErrorCode.ILLEGAL_TRANSITION
                            || e.code() == ErrorCode.ENV_NOT_FOUND) {
                        renewRejected.set(1);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            Thread sweeper = new Thread(() -> {
                try {
                    start.await();
                    h.reaper.sweepOnce();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            renewer.start();
            sweeper.start();
            start.countDown();
            renewer.join();
            sweeper.join();

            // Let any residual EXPIRED row finish too; second sweep must be no-op.
            int secondPass = h.reaper.sweepOnce();

            boolean exists = h.envRepo.findById(env.id()).isPresent();
            log.info("round={} decision renewOk={} renewRejected={} envExists={} secondPassRecycled={}",
                    r, renewOk.get(), renewRejected.get(), exists, secondPass);
            // Mutual exclusion: never renewed and removed together.
            assertFalse(renewOk.get() == 1 && !exists,
                    "renewed env must not simultaneously be recycled");
            // At the exact boundary the lease is expired: renewal must lose.
            assertEquals(0, renewOk.get(), "at t==expiresAt renewal must be rejected");
            assertEquals(1, renewRejected.get());
            assertFalse(exists, "env must be recycled exactly once");
            assertEquals(0, h.backend.listForEnv(env.id()).size());

            h.clock.advanceSeconds(1); // keep boundary strictly increasing per env
        }
        assertEquals(0, h.quotas.usage("alpha").activeEnvs(),
                "all expired envs returned quota");
        assertTrue(h.backend.listAll().isEmpty(), "no double-freed/leaked resources");
        log.info("input {} boundary rounds; decision: renew lost every round, no half-state",
                rounds);
    }

    @Test
    void renewalBeforeBoundaryBeatsTheSweepDeterministically() throws Exception {
        // One second before expiry: renew commits an extended lease, then sweep
        // must observe the new lease and leave the env READY.
        Environment env = h.fresh(h.alice(), "just-in-time");
        h.clock.advanceSeconds(3599);
        Environment renewed = h.envs.renew("alice", "alpha", env.id(), 3600L);
        int recycled = h.reaper.sweepOnce();
        log.info("input renew at t+3599 then sweep; decision recycled={} status={} expiry={}",
                recycled, renewed.status(), renewed.lease().expiresAt());
        assertEquals(0, recycled);
        assertEquals(EnvStatus.READY,
                h.envRepo.findById(env.id()).orElseThrow().status());
    }

    @Test
    void renewalExtendsLeaseFromDecisionTime() {
        Environment env = h.fresh(h.alice(), "extended");
        h.clock.advanceSeconds(1000);
        Environment renewed = h.envs.renew("alice", "alpha", env.id(), 3600L);
        assertEquals(h.clock.now().getEpochSecond() + 3600,
                renewed.lease().expiresAt().getEpochSecond());
        log.info("input renew at t+1000; decision newExpiry={}",
                renewed.lease().expiresAt());
    }

    @Test
    void invalidTtlIsRejected() {
        Environment env = h.fresh(h.alice(), "ttl-env");
        ApiException tooSmall = assertThrows(ApiException.class,
                () -> h.envs.renew("alice", "alpha", env.id(), 10L));
        assertEquals(ErrorCode.VALIDATION_ERROR, tooSmall.code());
        log.info("input ttl=10; decision code={}", tooSmall.code());
    }
}
