package com.github.xingjianluo.ephemeralenvcontrolplanejava.repo;

import com.github.xingjianluo.ephemeralenvcontrolplanejava.model.Environment;

import java.util.List;
import java.util.Optional;

/** In-memory store of environments, keyed by environment id and by (tenant,name). */
public interface EnvironmentRepository {

    Optional<Environment> findById(String id);

    Optional<Environment> find(String tenantId, String name);

    void save(Environment env);

    /** Remove a fully released environment so the name can be re-applied. */
    void delete(String id);

    List<Environment> findAll();

    List<Environment> findByTenant(String tenantId);

    /** Per-key lock used to serialize all operations on one environment. */
    Object lockFor(String id);

    // --- durable-state simulation used by restart/reconciliation tests ---

    List<Environment> snapshot();

    void reset();

    void restore(List<Environment> snapshot);
}
