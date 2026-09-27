package com.github.xingjianluo.ephemeralenvcontrolplanejava.service;

import com.github.xingjianluo.ephemeralenvcontrolplanejava.backend.ResourceBackend;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.model.Environment;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.model.EnvironmentStatus;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.model.SimResource;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.repo.EnvironmentRepository;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.web.dto.ReconcileReport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * 重启后的安全收敛器，两阶段且受 limit/maxMillis 双上限约束：
 *
 * 阶段一（孤立资源）：分页遍历模拟后端资源；其所属环境不存在、已 RELEASED
 * 或已 SUSPENDED（挂起环境不得持有计算资源，残留即泄漏）时删除。
 * 环境仍处于 READY 且未过期 -> 绝不动（不得误删有效环境）。
 *
 * 阶段二（卡住环境）：PENDING/PROVISIONING/FAILED/RELEASING/SUSPENDING/RESUMING
 * 的环境交给 EnvironmentService.recoverStuck 完成资源清理/重建与配额归还；
 * READY/SUSPENDED/RELEASED 跳过（SUSPENDED 是有效稳定态，绝不误改）。
 *
 * 超过预算立即停止并在报告中标记 truncatedByLimit，下轮继续，绝不无界执行。
 */
@Component
public class Reconciler {

    private static final Logger log = LoggerFactory.getLogger(Reconciler.class);
    private static final int PAGE_SIZE = 20;

    private final ResourceBackend backend;
    private final EnvironmentRepository repository;
    private final EnvironmentService environmentService;

    public Reconciler(ResourceBackend backend,
                      EnvironmentRepository repository,
                      EnvironmentService environmentService) {
        this.backend = backend;
        this.repository = repository;
        this.environmentService = environmentService;
    }

    public ReconcileReport reconcile(int limit, long maxMillis) {
        if (limit <= 0 || maxMillis <= 0) {
            throw new IllegalArgumentException("limit and maxMillis must be positive");
        }
        long deadline = System.currentTimeMillis() + maxMillis;
        ReconcileReport report = new ReconcileReport();
        int budget = limit;
        long now = System.currentTimeMillis();

        // 阶段一：孤立后端资源。
        int offset = 0;
        boolean done = false;
        while (budget > 0 && System.currentTimeMillis() < deadline) {
            List<SimResource> page = backend.listAll(offset, PAGE_SIZE);
            if (page.isEmpty()) {
                done = true;
                break;
            }
            for (SimResource r : page) {
                if (budget <= 0 || System.currentTimeMillis() >= deadline) {
                    break;
                }
                budget--;
                Optional<Environment> owner = repository.findById(r.envId());
                // 挂起环境必须已让出全部计算资源：其名下残留资源按孤立资源清理。
                boolean orphan = owner.isEmpty()
                        || owner.get().getStatus() == EnvironmentStatus.RELEASED
                        || owner.get().getStatus() == EnvironmentStatus.SUSPENDED;
                if (orphan) {
                    try {
                        if (backend.release(r.resourceId())) {
                            report.setOrphanResourcesRemoved(
                                    report.getOrphanResourcesRemoved() + 1);
                        }
                    } catch (Exception e) {
                        log.warn("orphan resource removal failed id={}: {}",
                                r.resourceId(), e.toString());
                    }
                }
            }
            offset += page.size();
            if (page.size() < PAGE_SIZE) {
                done = true;
                break;
            }
        }
        if (!done || budget <= 0 || System.currentTimeMillis() >= deadline) {
            report.setTruncatedByLimit(true);
            log.info("reconcile truncated after stage 1 budget={} elapsed={}",
                    budget, maxMillis - (deadline - System.currentTimeMillis()));
            return report;
        }

        // 阶段二：卡住/失败环境。
        offset = 0;
        done = false;
        while (budget > 0 && System.currentTimeMillis() < deadline) {
            List<Environment> page = repository.listAll(offset, PAGE_SIZE);
            if (page.isEmpty()) {
                done = true;
                break;
            }
            for (Environment env : page) {
                if (budget <= 0 || System.currentTimeMillis() >= deadline) {
                    break;
                }
                budget--;
                EnvironmentStatus st = env.getStatus();
                if (st == EnvironmentStatus.READY || st == EnvironmentStatus.RELEASED
                        || st == EnvironmentStatus.SUSPENDED) {
                    continue;
                }
                if (!environmentService.recoverStuck(env, now)) {
                    log.warn("stuck environment not fully recovered tenant={} env={} status={}",
                            env.getTenantId(), env.getName(), st);
                } else if (st != env.getStatus()) {
                    report.setStuckEnvironmentsRecovered(
                            report.getStuckEnvironmentsRecovered() + 1);
                }
            }
            offset += page.size();
            if (page.size() < PAGE_SIZE) {
                done = true;
                break;
            }
        }
        if (!done || budget <= 0 || System.currentTimeMillis() >= deadline) {
            report.setTruncatedByLimit(true);
        }
        log.info("reconcile done orphanRemoved={} stuckRecovered={} truncated={}",
                report.getOrphanResourcesRemoved(),
                report.getStuckEnvironmentsRecovered(),
                report.isTruncatedByLimit());
        return report;
    }
}
