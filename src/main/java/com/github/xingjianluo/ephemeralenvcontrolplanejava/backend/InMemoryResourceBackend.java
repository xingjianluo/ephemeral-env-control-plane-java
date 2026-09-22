package com.github.xingjianluo.ephemeralenvcontrolplanejava.backend;

import com.github.xingjianluo.ephemeralenvcontrolplanejava.error.BackendFailureException;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.model.ResourceSpec;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.model.SimResource;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;

/**
 * 基于内存 Map 的模拟资源后端。
 *
 * 特性：
 * - 线程安全的资源分配/释放，allocate 失败保证不留下资源（调用方看到异常即视为未分配）；
 * - release 幂等：资源不存在返回 false，不报错；
 * - 失败注入（供测试）：
 *   failAllocateRule：按 (envId,spec) 谓词让分配抛异常，用于制造“分配到一半失败”；
 *   failReleaseOnce：下一次 release/releaseAll 先删一部分再抛异常，
 *   制造“释放卡住”的中间态，供收敛器验证。
 */
@Component
public class InMemoryResourceBackend implements ResourceBackend {

    private final Map<String, SimResource> resources = new ConcurrentHashMap<>();
    private final AtomicLong idGen = new AtomicLong(1);

    private volatile Predicate<AllocateContext> allocateRule = ctx -> false;
    private volatile boolean failReleaseOnce = false;

    public record AllocateContext(String envId, ResourceSpec spec, int attempt) {
    }

    /** 安装分配失败规则（测试用）；传 null 清除。 */
    public void failAllocateRule(Predicate<AllocateContext> rule) {
        this.allocateRule = rule == null ? ctx -> false : rule;
    }

    /** 让下一次释放操作在删除部分资源后失败一次（测试收敛用）。 */
    public synchronized void failNextReleaseOnce() {
        this.failReleaseOnce = true;
    }

    @Override
    public synchronized SimResource allocate(String envId, ResourceSpec spec) {
        int attempt = (int) resources.values().stream().filter(r -> r.envId().equals(envId)).count() + 1;
        if (allocateRule.test(new AllocateContext(envId, spec, attempt))) {
            // 失败时不写入任何资源，保证无幽灵资源。
            throw new BackendFailureException(
                    "simulated allocation failure (env=" + envId + ", kind=" + spec.kind() + ")");
        }
        SimResource r = new SimResource(
                "res-" + idGen.getAndIncrement(), envId, spec, System.currentTimeMillis());
        resources.put(r.resourceId(), r);
        return r;
    }

    @Override
    public synchronized boolean release(String resourceId) {
        boolean existed = resources.remove(resourceId) != null;
        if (failReleaseOnce) {
            failReleaseOnce = false;
            throw new BackendFailureException("simulated release failure (resource=" + resourceId + ")");
        }
        return existed;
    }

    @Override
    public synchronized int releaseAll(String envId) {
        List<SimResource> mine = new ArrayList<>();
        for (SimResource r : resources.values()) {
            if (r.envId().equals(envId)) {
                mine.add(r);
            }
        }
        mine.sort(Comparator.comparing(SimResource::resourceId));
        int deleted = 0;
        for (SimResource r : mine) {
            resources.remove(r.resourceId());
            deleted++;
            // 若被要求“释放到一半失败”，在删除第一个后抛出，留下残留。
            if (failReleaseOnce && deleted == 1) {
                failReleaseOnce = false;
                throw new BackendFailureException(
                        "simulated release-all partial failure (env=" + envId + ")");
            }
        }
        return deleted;
    }

    @Override
    public synchronized List<SimResource> findByEnv(String envId) {
        return resources.values().stream()
                .filter(r -> r.envId().equals(envId))
                .sorted(Comparator.comparing(SimResource::resourceId))
                .toList();
    }

    @Override
    public synchronized List<SimResource> listAll(int offset, int limit) {
        if (limit <= 0 || offset < 0) {
            return List.of();
        }
        List<SimResource> all = resources.values().stream()
                .sorted(Comparator.comparing(SimResource::resourceId))
                .toList();
        if (offset >= all.size()) {
            return List.of();
        }
        return all.subList(offset, Math.min(offset + limit, all.size()));
    }

    public synchronized int size() {
        return resources.size();
    }
}
