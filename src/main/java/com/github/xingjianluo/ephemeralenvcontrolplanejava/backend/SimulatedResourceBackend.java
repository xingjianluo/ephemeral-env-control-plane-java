package com.github.xingjianluo.ephemeralenvcontrolplanejava.backend;

import com.github.xingjianluo.ephemeralenvcontrolplanejava.config.TimeSource;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.model.ResourceHandle;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Deterministic in-memory resource backend.
 *
 * <p>Failures are scripted with {@link #failAllocationAt}: the n-th allocation
 * (0-based unit index) for an environment throws, simulating a partially failed
 * provision. Any resources allocated earlier remain visible so the orchestrator
 * (and post-restart reconciliation) must clean them up.</p>
 */
public class SimulatedResourceBackend implements ResourceBackend {

    private final TimeSource timeSource;
    private final ConcurrentMap<String, ResourceHandle> resources = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Set<Integer>> failAt = new ConcurrentHashMap<>();

    public SimulatedResourceBackend(TimeSource timeSource) {
        this.timeSource = timeSource;
    }

    @Override
    public ResourceHandle allocate(String envId, String kind) {
        // Allocation index = number of resources this env already holds.
        int index = (int) resources.values().stream()
                .filter(h -> h.envId().equals(envId))
                .count();
        Set<Integer> failing = failAt.get(envId);
        if (failing != null && failing.contains(index)) {
            throw new BackendException(
                    "simulated allocation failure: env=" + envId + " unit=" + index);
        }
        String resourceId = envId + "-" + kind + "-" + index;
        ResourceHandle handle =
                new ResourceHandle(resourceId, envId, kind, timeSource.now());
        resources.put(resourceId, handle);
        return handle;
    }

    @Override
    public void deallocate(ResourceHandle handle) {
        // Idempotent: re-deleting / unknown resource is a silent no-op.
        if (handle == null) {
            return;
        }
        resources.remove(handle.resourceId());
    }

    @Override
    public List<ResourceHandle> listForEnv(String envId) {
        return resources.values().stream()
                .filter(h -> h.envId().equals(envId))
                .toList();
    }

    @Override
    public List<ResourceHandle> listAll() {
        return List.copyOf(resources.values());
    }

    /** Make the n-th allocation (0-based unit index) for the env throw. */
    public void failAllocationAt(String envId, int unitIndex) {
        failAt.computeIfAbsent(envId, k -> ConcurrentHashMap.newKeySet()).add(unitIndex);
    }

    public void clearFailures() {
        failAt.clear();
    }

    /** Test-only: remove every simulated resource. */
    public void reset() {
        resources.clear();
        failAt.clear();
    }
}
