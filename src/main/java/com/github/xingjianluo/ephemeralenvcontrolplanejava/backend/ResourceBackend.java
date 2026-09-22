package com.github.xingjianluo.ephemeralenvcontrolplanejava.backend;

import com.github.xingjianluo.ephemeralenvcontrolplanejava.model.ResourceHandle;

import java.util.List;

/**
 * Simulated resource backend. Stands in for a real cluster/cloud API so the
 * whole lifecycle can be verified locally with no external services.
 */
public interface ResourceBackend {

    ResourceHandle allocate(String envId, String kind);

    /** Releasing an unknown/already-released resource is a no-op (idempotent). */
    void deallocate(ResourceHandle handle);

    List<ResourceHandle> listForEnv(String envId);

    List<ResourceHandle> listAll();
}
