package com.github.xingjianluo.ephemeralenvcontrolplanejava.repo;

import com.github.xingjianluo.ephemeralenvcontrolplanejava.model.Environment;
import org.springframework.stereotype.Repository;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 内存环境仓库。
 * 主索引为 租户 + ":" + 环境名（命名按租户隔离）；另有 id 二级索引。
 * 线程安全：save 时双索引以相同键顺序（先复合键后 id）更新；
 * 业务临界区由 EnvironmentService 的租户锁保证，索引本身不会被并发破坏。
 */
@Repository
public class InMemoryEnvironmentRepository implements EnvironmentRepository {

    private final ConcurrentMap<String, Environment> byKey = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, String> idToKey = new ConcurrentHashMap<>();

    private static String key(String tenantId, String name) {
        return tenantId + ":" + name;
    }

    @Override
    public Optional<Environment> find(String tenantId, String name) {
        return Optional.ofNullable(byKey.get(key(tenantId, name)));
    }

    @Override
    public Optional<Environment> findById(String envId) {
        String k = idToKey.get(envId);
        return k == null ? Optional.empty() : Optional.ofNullable(byKey.get(k));
    }

    @Override
    public void save(Environment env) {
        String k = key(env.getTenantId(), env.getName());
        byKey.put(k, env);
        idToKey.put(env.getId(), k);
    }

    @Override
    public List<Environment> list(String tenantId, int limit) {
        if (limit <= 0) {
            return List.of();
        }
        return byKey.entrySet().stream()
                .filter(e -> e.getKey().startsWith(tenantId + ":"))
                .map(java.util.Map.Entry::getValue)
                .limit(limit)
                .toList();
    }

    @Override
    public List<Environment> listAll(int offset, int limit) {
        if (limit <= 0 || offset < 0) {
            return List.of();
        }
        // 按内部 id 稳定排序后分页，保证扫描逐页推进、不会饥饿也不会无界加载。
        List<Environment> all = byKey.values().stream()
                .sorted(Comparator.comparing(Environment::getId))
                .toList();
        if (offset >= all.size()) {
            return List.of();
        }
        return all.subList(offset, Math.min(offset + limit, all.size()));
    }
}
