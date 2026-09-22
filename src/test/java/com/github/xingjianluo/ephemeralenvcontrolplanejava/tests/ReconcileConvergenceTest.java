package com.github.xingjianluo.ephemeralenvcontrolplanejava.tests;

import com.github.xingjianluo.ephemeralenvcontrolplanejava.model.Environment;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.model.EnvStatus;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.model.ResourceHandle;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.service.ApplyCommand;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ReconcileConvergenceTest {

    private static final Logger log = LoggerFactory.getLogger(ReconcileConvergenceTest.class);
    private final TestHarness h = new TestHarness();

    @Test
    void partialFailureLeavesQueryableStateAndNoLeak() {
        // 3 units; second unit fails. First unit must be rolled back, env FAILED.
        String id = h.envId("alpha", "partial");
        h.backend.failAllocationAt(id, 1);
        ApplyResultLike r = apply(id, "partial", "3");

        Environment env = h.envRepo.findById(id).orElseThrow();
        log.info("input replicas=3 failUnit=1; decision status={} reason='{}' backendLeft={} unitsHeld={}",
                env.status(), env.failureReason(), h.backend.listAll().size(),
                h.quotas.usage("alpha").usedUnits());
        assertEquals(EnvStatus.FAILED, env.status());
        assertTrue(env.failureReason().contains("unit=1"));
        assertTrue(h.backend.listAll().isEmpty(), "rolled-back unit must not leak");
        assertEquals(0, h.quotas.usage("alpha").usedUnits());
        // Failed env is still queryable for diagnosis.
        Environment queried = h.envs.get("alice", "alpha", id, false);
        assertEquals(EnvStatus.FAILED, queried.status());
    }

    @Test
    void orphanBackendResourcesAfterRestartAreConverged() {
        // Simulate a deleted/never-recorded backend resource (ghost from a crash
        // before durable state was written): create an env, snapshot, then wipe
        // its durable row but keep backend resources.
        Environment env = h.fresh(h.alice(), "orphaned");
        assertEquals(1, h.backend.listAll().size());
        h.envRepo.reset();
        h.quotas.recompute(h.envRepo.findAll()); // restart: ledger rebuilt empty
        log.info("input simulated restart with orphan backend resources={}; liveEnvs=0",
                h.backend.listAll().size());

        // While a valid env also exists, it must NOT be touched.
        Environment live = h.fresh(h.bob(), "live-env");
        int beforeLive = h.backend.listForEnv(live.id()).size();

        int removed = h.reconciler.reconcileOrphans();
        log.info("decision orphansRemoved={} liveEnvResourcesKept={}",
                removed, h.backend.listForEnv(live.id()).size());
        assertEquals(1, removed);
        assertTrue(h.backend.listForEnv(env.id()).isEmpty(),
                "orphan resources must be deallocated");
        assertEquals(beforeLive, h.backend.listForEnv(live.id()).size(),
                "in-lease environment must never be reaped by reconciliation");
        assertEquals(EnvStatus.READY,
                h.envRepo.findById(live.id()).orElseThrow().status());
    }

    @Test
    void stuckReleasingAfterCrashIsCompletedAndQuotaRestored() {
        // Build an env then emulate a crash in the middle of release: durable
        // state says RELEASING, backend still has the resource, quota still held.
        Environment env = h.fresh(h.alice(), "half-released");
        String id = env.id();
        assertEquals(1, h.quotas.usage("alpha").activeEnvs());

        // Reach RELEASING manually (as an interrupted doRelease would), keeping
        // the backend resource and quota occupied.
        env.transition(EnvStatus.RELEASING, h.clock.now());
        h.envRepo.save(env);
        assertEquals(1, h.backend.listForEnv(id).size());
        log.info("input interrupted RELEASING env={}; backendResources=1 quota=1", id);

        int completed = h.reconciler.reconcileStuckReleases();
        assertEquals(1, completed);
        assertTrue(h.envRepo.findById(id).isEmpty(), "stuck release driven to RELEASED");
        assertTrue(h.backend.listForEnv(id).isEmpty());
        assertEquals(0, h.quotas.usage("alpha").activeEnvs(),
                "no ghost quota after convergence");
        log.info("decision stuckCompleted=1 resourcesCleaned quotaReturned");

        // Re-running is harmless (idempotent convergence).
        assertEquals(0, h.reconciler.reconcileStuckReleases());
    }

    @Test
    void readyEnvironmentsSurviveFullReconcile() {
        h.fresh(h.alice(), "survive-1");
        h.fresh(h.alice(), "survive-2");
        int orphans = h.reconciler.reconcileOrphans();
        int stuck = h.reconciler.reconcileStuckReleases();
        log.info("input reconcile with only READY envs; decision orphans={} stuck={}",
                orphans, stuck);
        assertEquals(0, orphans);
        assertEquals(0, stuck);
        assertEquals(2, h.backend.listAll().size());
        assertEquals(2, h.quotas.usage("alpha").activeEnvs());
    }

    private ApplyResultLike apply(String id, String name, String replicas) {
        h.backend.failAllocationAt(id, 1);
        h.envs.apply(new ApplyCommand(h.alice(), name, "demo", "2",
                Map.of("replicas", replicas), 3600L));
        return new ApplyResultLike();
    }

    private record ApplyResultLike() {
    }
}
