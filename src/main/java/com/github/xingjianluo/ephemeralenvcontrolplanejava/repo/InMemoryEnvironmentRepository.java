package com.github.xingjianluo.ephemeralenvcontrolplanejava.repo;

import com.github.xingjianluo.ephemeralenvcontrolplanejava.model.Environment;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * In-memory environment store with a stable id per (tenant, name) so that
 * duplicate applies serialize on the same striped lock and return the same
 * environment. Snapshot/restore simulates process restart against a durable
 * state copy while the backend resource store outlives it.
 */
public class InMemoryEnvironmentRepository implements EnvironmentRepository {

    private static final int STRIPES = 32;
    private static final String SEP = "--";

    private final Object[] locks = new Object[STRIPES];
    private final ConcurrentMap<String, Environment> byId = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, String> nameIndex = new ConcurrentHashMap<>();

    public InMemoryEnvironmentRepository() {
        for (int i = 0; i < STRIPES; i++) {
            locks[i] = new Object();
        }
    }

    /** Deterministic environment identity for a tenant-scoped name. */
    public static String deterministicId(String tenantId, String name) {
        return tenantId + SEP + name;
    }

    private static String nameKey(String tenantId, String name) {
        return tenantId + SEP + name;
    }

    @Override
    public Optional<Environment> findById(String id) {
        return Optional.ofNullable(byId.get(id));
    }

    @Override
    public Optional<Environment> find(String tenantId, String name) {
        String id = nameIndex.get(nameKey(tenantId, name));
        return id == null ? Optional.empty() : Optional.ofNullable(byId.get(id));
    }

    @Override
    public void save(Environment env) {
        byId.put(env.id(), env);
        nameIndex.put(nameKey(env.tenantId(), env.name()), env.id());
    }

    /** Remove a fully released environment so the name can be re-applied. */
    public void delete(String id) {
        Environment env = byId.remove(id);
        if (env != null) {
            nameIndex.remove(nameKey(env.tenantId(), env.name()), id);
        }
    }

    @Override
    public List<Environment> findAll() {
        return new ArrayList<>(byId.values());
    }

    @Override
    public List<Environment> findByTenant(String tenantId) {
        return byId.values().stream()
                .filter(e -> e.tenantId().equals(tenantId))
                .toList();
    }

    @Override
    public Object lockFor(String id) {
        return locks[Math.floorMod(id.hashCode(), STRIPES)];
    }

    @Override
    public List<Environment> snapshot() {
        return new ArrayList<>(byId.values());
    }

    @Override
    public void reset() {
        byId.clear();
        nameIndex.clear();
    }

    @Override
    public void restore(List<Environment> snapshot) {
        reset();
        snapshot.forEach(this::save);
    }
}
