package com.github.xingjianluo.ephemeralenvcontrolplanejava.tests;

import com.github.xingjianluo.ephemeralenvcontrolplanejava.error.BackendFailureException;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.model.Environment;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.model.EnvironmentStatus;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.model.Role;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.model.SimResource;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.web.dto.ReconcileReport;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 部分失败后的诊断与收敛：
 * - 释放失败留下 RELEASING 中间态，可重试完成，不重复归还配额；
 * - 重启后孤立资源（所属环境已 RELEASED/不存在）被安全删除；
 * - 卡住状态可被收敛，仍有效（READY 未过期）的环境绝不动；
 * - 扫描受 maxBatch/maxMillis 约束。
 */
class ReconciliationTest extends AbstractControlPlaneTest {

    @Test
    void partialReleaseLeavesIntermediateStateThenReconcileCompletes() {
        var bob = subject("bob", "team-a", Role.OWNER);
        Environment env = envService.apply(bob, request("team-a", "half-rel", "bob",
                "v1", 60L, smallSpecs(), null));
        assertEquals(1, backend.size());
        backend.failNextReleaseOnce(); // 下一次释放抛错（资源仍可能被删掉一部分）

        testLog.info("TEST partial release: release throws but state must stay RELEASING");
        assertThrows(BackendFailureException.class,
                () -> envService.release(bob, "team-a", "half-rel", "retry me"));

        Environment stuck = envService.get(bob, "team-a", "half-rel");
        assertEquals(EnvironmentStatus.RELEASING, stuck.getStatus(),
                "释放部分失败必须留下可查询中间态而非成功假象");
        assertNotNull(stuck.getFailureReason());
        assertEquals(1, quotaService.getQuota("team-a").getUsedEnvs(),
                "未完成释放前配额不归还");

        // 收敛：应完成剩余资源清理与配额归还。
        ReconcileReport report = reconciler.reconcile(50, 1000);
        Environment done = envService.get(bob, "team-a", "half-rel");
        assertEquals(EnvironmentStatus.RELEASED, done.getStatus());
        assertEquals(0, backend.size());
        assertEquals(0, quotaService.getQuota("team-a").getUsedEnvs());
        assertTrue(report.getStuckEnvironmentsRecovered() >= 1);
        testLog.info("判定依据: 收敛后 status=RELEASED, backend=0, usedEnvs=0");
    }

    @Test
    void orphanBackendResourceWhoseEnvironmentIsReleasedIsRemoved() {
        var bob = subject("bob", "team-a", Role.OWNER);
        Environment env = envService.apply(bob, request("team-a", "orphan1", "bob",
                "v1", 60L, smallSpecs(), null));
        // 正常回收后，手工注入一个“遗留资源”（模拟崩溃残留 / 外部幽灵）。
        envService.release(bob, "team-a", "orphan1", "ok");
        assertEquals(0, backend.size());
        SimResource ghost = backend.allocate(env.getId(), smallSpecs().get(0));
        assertEquals(1, backend.size());

        ReconcileReport report = reconciler.reconcile(50, 1000);
        assertEquals(0, backend.size(), "已 RELEASED 环境的孤立资源必须被清理");
        assertEquals(1, report.getOrphanResourcesRemoved());
    }

    @Test
    void orphanResourceForMissingEnvironmentIsRemoved() {
        // 直接在后端创建一个不属于任何已登记环境的资源。
        SimResource dangling = backend.allocate("missing-env-id", smallSpecs().get(0));
        testLog.info("TEST dangling resource envId={} -> expect removed", dangling.envId());
        ReconcileReport report = reconciler.reconcile(50, 1000);
        assertEquals(0, backend.size());
        assertEquals(1, report.getOrphanResourcesRemoved());
    }

    @Test
    void reconcilerNeverTouchesReadyUnexpiredEnvironments() {
        var bob = subject("bob", "team-a", Role.OWNER);
        Environment env = envService.apply(bob, request("team-a", "live", "bob",
                "v1", 600L, smallSpecs(), null));
        clock.advance(10_000); // 远未到期
        ReconcileReport report = reconciler.reconcile(50, 1000);
        Environment still = envService.get(bob, "team-a", "live");
        assertEquals(EnvironmentStatus.READY, still.getStatus(),
                "READY 且未过期的环境绝不能被收敛误删");
        assertEquals(1, backend.size(), "有效环境的资源必须保留");
        assertEquals(0, report.getOrphanResourcesRemoved());
        assertEquals(0, report.getStuckEnvironmentsRecovered());
        testLog.info("判定依据: 收敛后 live 环境仍 READY，backendSize=1");
    }

    @Test
    void stuckProvisioningEnvironmentIsRecoveredAndQuotaReturned() {
        var bob = subject("bob", "team-a", Role.OWNER);
        // 制造 FAILED 环境（第二个资源分配失败），随后模拟重启发现残留资源。
        backend.failAllocateRule(ctx -> ctx.attempt() == 2);
        assertThrows(BackendFailureException.class, () -> envService.apply(
                bob, request("team-a", "stuck", "bob", "v1", 60L,
                        java.util.List.of(
                                new com.github.xingjianluo.ephemeralenvcontrolplanejava.model.ResourceSpec(
                                        "a", "i", 1, 256),
                                new com.github.xingjianluo.ephemeralenvcontrolplanejava.model.ResourceSpec(
                                        "b", "i", 1, 256)),
                        null)));
        Environment failed = envService.get(bob, "team-a", "stuck");
        assertEquals(EnvironmentStatus.FAILED, failed.getStatus());
        SimResource leftover = backend.allocate(failed.getId(), smallSpecs().get(0));
        assertEquals(1, backend.size(), "模拟重启后发现 FAILED 环境仍有残留资源");

        reconciler.reconcile(50, 1000);
        assertEquals(0, backend.size(), "FAILED 环境的残留资源必须被清理");
        assertEquals(0, quotaService.getQuota("team-a").getUsedEnvs(), "配额不得幽灵占用");
        assertEquals(EnvironmentStatus.FAILED, envService.get(bob, "team-a", "stuck").getStatus(),
                "失败环境保留 FAILED 可查询状态，而不是伪装成功");
        testLog.info("判定依据: 残留资源 {} 已清理，status 仍为 FAILED（可诊断）", leftover.resourceId());
    }

    @Test
    void scannerRespectsBatchAndTimeBudget() {
        var bob = subject("bob", "team-a", Role.OWNER);
        // 准备 3 个已到期环境（team-a 配额只有 2，借用 team-b 一个）。
        var dave = subject("dave", "team-b", Role.TENANT_ADMIN);
        envService.apply(bob, request("team-a", "e1", "bob", "v1", 10L, smallSpecs(), null));
        envService.apply(bob, request("team-a", "e2", "bob", "v1", 10L, smallSpecs(), null));
        envService.apply(dave, request("team-b", "e3", "dave", "v1", 10L, smallSpecs(), null));
        clock.advance(10_000);

        testLog.info("TEST scanner budget: maxBatch=2, expect 2 expired this round, 1 remaining");
        int expired = scanner.scanOnce(2, 10_000);
        assertEquals(2, expired, "本轮最多回收 2 个（受 maxBatch 限制）");
        long readyOrReleased = envRepo.listAll(0, 100).stream()
                .filter(e -> e.getStatus() == EnvironmentStatus.READY).count();
        assertEquals(1, readyOrReleased, "第 3 个到期环境留待下一轮，不能无界扫描");

        int second = scanner.scanOnce(50, 10_000);
        assertEquals(1, second, "下一轮继续完成剩余回收");
        assertEquals(0, backend.size(), "三轮之后所有到期资源均被释放");
    }
}
