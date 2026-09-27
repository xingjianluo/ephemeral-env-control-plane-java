package com.github.xingjianluo.ephemeralenvcontrolplanejava.tests;

import com.github.xingjianluo.ephemeralenvcontrolplanejava.error.BackendFailureException;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.error.ForbiddenException;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.error.InvalidStateException;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.error.QuotaExceededException;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.model.ActionType;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.model.Environment;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.model.EnvironmentStatus;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.model.Role;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 挂起 / 恢复行为测试：
 * 幂等与配置稳定性、配额归还与恢复竞争、与到期/回收的并发确定性、
 * 越权与审计、失败中间态与重启收敛。全部基于本地模拟组件与可推进时钟。
 */
class SuspendResumeTest extends AbstractControlPlaneTest {

    // ------------------------------------------------------------ 幂等与配置稳定性

    @Test
    void suspendAndResumeAreIdempotentAndConfigStaysFrozen() {
        var bob = subject("bob", "team-a", Role.OWNER);
        Environment env = envService.apply(bob, request("team-a", "sr-idem", "bob",
                "v1", 300L, smallSpecs(), null));
        assertEquals(EnvironmentStatus.READY, env.getStatus());
        Map<String, Object> frozenConfig = env.getConfigValues();
        testLog.info("输入: apply sr-idem v1 -> READY, frozenConfig={}", frozenConfig);

        // 挂起 + 重复挂起（幂等）
        Environment s1 = envService.suspend(bob, "team-a", "sr-idem");
        Environment s2 = envService.suspend(bob, "team-a", "sr-idem");
        testLog.info("判定依据: suspend x2 -> status={}/{}, backendSize={}, usedEnvs={}",
                s1.getStatus(), s2.getStatus(), backend.size(),
                quotaService.getQuota("team-a").getUsedEnvs());
        assertEquals(EnvironmentStatus.SUSPENDED, s1.getStatus());
        assertEquals(EnvironmentStatus.SUSPENDED, s2.getStatus(), "重复挂起必须幂等返回挂起态");
        assertEquals(0, backend.size(), "挂起后计算资源必须释放");
        assertEquals(0, quotaService.getQuota("team-a").getUsedEnvs(), "挂起后配额必须归还");
        assertTrue(s1.getSuspendedAtMillis() > 0, "挂起时刻必须记录");
        // 环境本体仍可查，归属/身份/配置快照不变
        Environment visible = envService.get(bob, "team-a", "sr-idem");
        assertEquals("bob", visible.getOwner());
        assertEquals("v1", visible.getTemplateVersion());
        assertEquals(frozenConfig, visible.getConfigValues(), "挂起不得改变固化配置快照");

        // 挂起之后注册的新模板版本不得追溯影响该环境
        templates.register("v3", "dbPassword",
                Map.of("image", "app:1.0", "replicas", 1, "dbPassword", "secret-v1",
                        "featureFlag", false, "newSetting", "x"));
        // 恢复 + 重复恢复（幂等）
        Environment r1 = envService.resume(bob, "team-a", "sr-idem");
        Environment r2 = envService.resume(bob, "team-a", "sr-idem");
        testLog.info("判定依据: resume x2 -> status={}/{}, backendSize={}, usedEnvs={}, config={}",
                r1.getStatus(), r2.getStatus(), backend.size(),
                quotaService.getQuota("team-a").getUsedEnvs(), r1.getConfigValues());
        assertEquals(EnvironmentStatus.READY, r1.getStatus());
        assertEquals(EnvironmentStatus.READY, r2.getStatus(), "重复恢复必须幂等返回就绪态");
        assertEquals(1, backend.size(), "恢复只重建一份资源，不得重复分配");
        assertEquals(1, quotaService.getQuota("team-a").getUsedEnvs(), "恢复只占用一份配额");
        assertEquals(0, r1.getSuspendedAtMillis(), "恢复后挂起时刻清零");
        assertEquals("v1", r1.getTemplateVersion(), "模板版本保持挂起前固化值");
        assertEquals(frozenConfig, r1.getConfigValues(),
                "挂起后注册的新模板版本不得追溯改变已固化的配置快照");
    }

    // ------------------------------------------------------------ 配额归还与恢复竞争

    @Test
    void suspendReturnsQuotaAndResumeLosesRaceStaysSuspended() {
        var bob = subject("bob", "team-a", Role.OWNER);
        var alice = subject("alice", "team-a", Role.TENANT_ADMIN);
        // team-a 配额：2 环境 / 4 CPU / 2048MB
        envService.apply(bob, request("team-a", "sr-q1", "bob", "v1", 300L, smallSpecs(), null));
        envService.apply(alice, request("team-a", "sr-q2", "alice", "v1", 300L, smallSpecs(), null));
        assertEquals(2, quotaService.getQuota("team-a").getUsedEnvs());
        testLog.info("输入: team-a 配额(2,4,2048) 已被 sr-q1/sr-q2 占满");

        // 挂起 sr-q1 让出配额 -> 第三个环境可以申请成功
        envService.suspend(bob, "team-a", "sr-q1");
        assertEquals(1, quotaService.getQuota("team-a").getUsedEnvs(), "挂起必须归还配额");
        envService.apply(alice, request("team-a", "sr-q3", "alice", "v1", 300L, smallSpecs(), null));
        assertEquals(2, quotaService.getQuota("team-a").getUsedEnvs());
        testLog.info("判定依据: 挂起 sr-q1 后 sr-q3 申请成功, usedEnvs=2");

        // 此刻恢复 sr-q1：配额已被占满 -> 明确拒绝，环境稳定停在 SUSPENDED
        QuotaExceededException rejected = assertThrows(QuotaExceededException.class,
                () -> envService.resume(bob, "team-a", "sr-q1"),
                "配额不足时恢复必须被明确拒绝");
        Environment after = envService.get(bob, "team-a", "sr-q1");
        testLog.info("判定依据: resume 被拒绝({}), status={}, usedEnvs={}, backendSize={}",
                rejected.getMessage(), after.getStatus(),
                quotaService.getQuota("team-a").getUsedEnvs(), backend.size());
        assertEquals(EnvironmentStatus.SUSPENDED, after.getStatus(),
                "恢复被拒绝后环境必须稳定停在挂起态以便重试");
        assertEquals(2, quotaService.getQuota("team-a").getUsedEnvs(),
                "被拒绝的恢复不得留下幽灵配额占用");
        assertEquals(2, backend.size(), "被拒绝的恢复不得留下幽灵资源");

        // 腾出配额后重试恢复 -> 成功
        envService.release(alice, "team-a", "sr-q3", "make room");
        Environment resumed = envService.resume(bob, "team-a", "sr-q1");
        assertEquals(EnvironmentStatus.READY, resumed.getStatus(), "配额可用后重试恢复必须成功");
        assertEquals(2, quotaService.getQuota("team-a").getUsedEnvs());
    }

    // ------------------------------------------------------------ 与到期/回收的确定性联动

    @Test
    void suspendAfterExpiryReleasesInstead() {
        var bob = subject("bob", "team-a", Role.OWNER);
        Environment env = envService.apply(bob, request("team-a", "sr-exp", "bob",
                "v1", 60L, smallSpecs(), null));
        clock.advance(61_000); // 已到期
        testLog.info("输入: sr-exp 已到期(now >= expiresAt={}), 发起挂起", env.getExpiresAtMillis());

        assertThrows(InvalidStateException.class,
                () -> envService.suspend(bob, "team-a", "sr-exp"),
                "已到期的环境挂起必须失败");
        Environment after = envService.get(bob, "team-a", "sr-exp");
        testLog.info("判定依据: status={}, backendSize={}, usedEnvs={}",
                after.getStatus(), backend.size(), quotaService.getQuota("team-a").getUsedEnvs());
        assertEquals(EnvironmentStatus.RELEASED, after.getStatus(),
                "到期与挂起竞争的结果必须是回收（确定性地只有一个结果）");
        assertEquals(0, backend.size());
        assertEquals(0, quotaService.getQuota("team-a").getUsedEnvs());
    }

    @Test
    void resumeAfterExpiryReleasesInstead() {
        var bob = subject("bob", "team-a", Role.OWNER);
        Environment env = envService.apply(bob, request("team-a", "sr-rexp", "bob",
                "v1", 60L, smallSpecs(), null));
        envService.suspend(bob, "team-a", "sr-rexp");
        clock.advance(61_000); // 挂起期间到期：挂起不免回收
        testLog.info("输入: sr-rexp 挂起期间到期(expiresAt={}), 发起恢复", env.getExpiresAtMillis());

        assertThrows(InvalidStateException.class,
                () -> envService.resume(bob, "team-a", "sr-rexp"),
                "挂起期间到期的环境恢复必须失败");
        Environment after = envService.get(bob, "team-a", "sr-rexp");
        testLog.info("判定依据: status={}, usedEnvs={}",
                after.getStatus(), quotaService.getQuota("team-a").getUsedEnvs());
        assertEquals(EnvironmentStatus.RELEASED, after.getStatus(),
                "恢复与到期回收只能有一个确定结果：回收赢");
        assertEquals(0, quotaService.getQuota("team-a").getUsedEnvs(),
                "到期清理不得留下配额占用");
    }

    @Test
    void suspendedEnvIsCleanedByExpiryScanner() {
        var bob = subject("bob", "team-a", Role.OWNER);
        Environment env = envService.apply(bob, request("team-a", "sr-scan", "bob",
                "v1", 60L, smallSpecs(), null));
        envService.suspend(bob, "team-a", "sr-scan");
        clock.advance(61_000);
        testLog.info("输入: sr-scan 挂起中且租约到期, 运行到期扫描");

        int expired = scanner.scanOnce(50, 1_000);
        Environment after = envService.get(bob, "team-a", "sr-scan");
        boolean expireAudited = auditRepo.findByEnv("team-a", env.getId(), 100).stream()
                .anyMatch(r -> r.action() == ActionType.EXPIRE
                        && r.after() == EnvironmentStatus.RELEASED
                        && r.reason().contains("suspended"));
        testLog.info("判定依据: expired={}, status={}, expireAudited={}",
                expired, after.getStatus(), expireAudited);
        assertEquals(1, expired, "扫描必须覆盖挂起中的到期环境");
        assertEquals(EnvironmentStatus.RELEASED, after.getStatus());
        assertTrue(expireAudited, "挂起中的到期清理必须留下审计记录");
    }

    @Test
    void suspendedEnvIsCleanedWhenRetentionExceeded() {
        var bob = subject("bob", "team-a", Role.OWNER);
        props.getSuspend().setMaxRetentionMs(10_000);
        Environment env = envService.apply(bob, request("team-a", "sr-ret", "bob",
                "v1", 300L, smallSpecs(), null));
        envService.suspend(bob, "team-a", "sr-ret");
        clock.advance(11_000); // 超过挂起保留期，但租约(300s)远未到期
        testLog.info("输入: sr-ret 挂起超过保留期(10000ms), 租约未到期, 运行扫描");

        int expired = scanner.scanOnce(50, 1_000);
        Environment after = envService.get(bob, "team-a", "sr-ret");
        boolean retentionAudited = auditRepo.findByEnv("team-a", env.getId(), 100).stream()
                .anyMatch(r -> r.action() == ActionType.EXPIRE
                        && r.reason().contains("retention"));
        testLog.info("判定依据: expired={}, status={}, retentionAudited={}",
                expired, after.getStatus(), retentionAudited);
        assertEquals(1, expired, "挂起环境不得无界堆积：超保留期必须被清理");
        assertEquals(EnvironmentStatus.RELEASED, after.getStatus());
        assertTrue(retentionAudited, "保留期清理必须留下可查询审计");
    }

    @Test
    void resumeAndExpiryRaceHasSingleDeterministicOutcome() throws Exception {
        var bob = subject("bob", "team-a", Role.OWNER);
        Environment env = envService.apply(bob, request("team-a", "sr-race", "bob",
                "v1", 60L, smallSpecs(), null));
        envService.suspend(bob, "team-a", "sr-race");
        clock.advance(61_000); // 已到期：恢复与扫描同时发生

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<Throwable> resumeFailure = new AtomicReference<>();
        int[] expiredCount = {0};
        testLog.info("TEST race: {} 线程混合 恢复/扫描 (挂起中且已到期)", threads);
        for (int i = 0; i < threads; i++) {
            boolean doResume = i % 2 == 0;
            pool.submit(() -> {
                try {
                    start.await();
                    if (doResume) {
                        envService.resume(bob, "team-a", "sr-race");
                    } else {
                        if (envService.expireIfDue(env)) {
                            expiredCount[0]++;
                        }
                    }
                } catch (InvalidStateException e) {
                    resumeFailure.compareAndSet(null, e); // 恢复方必须看到“已到期回收”
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(20, TimeUnit.SECONDS));

        Environment after = envService.get(bob, "team-a", "sr-race");
        long releaseAudits = auditRepo.findByEnv("team-a", env.getId(), 100).stream()
                .filter(r -> r.after() == EnvironmentStatus.RELEASED)
                .count();
        testLog.info("判定依据: status={}, scannerExpired={}, resumeFailed={}, releaseAudits={}, usedEnvs={}",
                after.getStatus(), expiredCount[0], resumeFailure.get() != null,
                releaseAudits, quotaService.getQuota("team-a").getUsedEnvs());
        assertEquals(EnvironmentStatus.RELEASED, after.getStatus(),
                "恢复与回收并发只能有一个确定结果：到期回收赢");
        assertEquals(1, releaseAudits, "回收恰好发生一次，不得半更新");
        assertNotNull(resumeFailure.get(), "并发恢复必须被明确拒绝而不是假装成功");
        assertEquals(0, quotaService.getQuota("team-a").getUsedEnvs(),
                "不得出现资源已释放但配额仍被占的半更新状态");
        assertEquals(0, backend.size());
    }

    @Test
    void renewOnSuspendedIsRejectedAndStateUnchanged() {
        var bob = subject("bob", "team-a", Role.OWNER);
        envService.apply(bob, request("team-a", "sr-renew", "bob", "v1", 60L, smallSpecs(), null));
        envService.suspend(bob, "team-a", "sr-renew");
        testLog.info("输入: sr-renew 挂起中, 发起续期");

        assertThrows(InvalidStateException.class,
                () -> envService.renew(bob, "team-a", "sr-renew", 60L),
                "挂起中的环境不能直接续期（须先恢复）");
        Environment after = envService.get(bob, "team-a", "sr-renew");
        testLog.info("判定依据: status={}, expiresAt={}", after.getStatus(), after.getExpiresAtMillis());
        assertEquals(EnvironmentStatus.SUSPENDED, after.getStatus(), "续期被拒后状态必须保持挂起");
    }

    // ------------------------------------------------------------ 越权与审计

    @Test
    void unauthorizedSuspendResumeAreRejectedWithoutLeakage() {
        var bob = subject("bob", "team-a", Role.OWNER);
        var carol = subject("carol", "team-a", Role.VIEWER);
        var dave = subject("dave", "team-b", Role.TENANT_ADMIN);
        envService.apply(bob, request("team-a", "sr-acl", "bob", "v1", 300L, smallSpecs(), null));
        testLog.info("输入: VIEWER carol 与他租户管理员 dave 分别尝试挂起/恢复 sr-acl");

        // VIEWER 无挂起权限
        ForbiddenException f1 = assertThrows(ForbiddenException.class,
                () -> envService.suspend(carol, "team-a", "sr-acl"));
        // 跨租户一律拒绝，且错误文案不泄露环境是否存在
        ForbiddenException f2 = assertThrows(ForbiddenException.class,
                () -> envService.suspend(dave, "team-a", "sr-acl"));
        testLog.info("判定依据: viewerMsg='{}', crossTenantMsg='{}'", f1.getMessage(), f2.getMessage());
        assertEquals("access denied", f2.getMessage(),
                "跨租户拒绝必须是通用文案，不泄露环境存在性");
        Environment after = envService.get(bob, "team-a", "sr-acl");
        assertEquals(EnvironmentStatus.READY, after.getStatus(), "越权尝试不得改变环境状态");

        // 越权恢复同样被拒
        envService.suspend(bob, "team-a", "sr-acl");
        assertThrows(ForbiddenException.class, () -> envService.resume(carol, "team-a", "sr-acl"));
        assertThrows(ForbiddenException.class, () -> envService.resume(dave, "team-a", "sr-acl"));
        assertEquals(EnvironmentStatus.SUSPENDED,
                envService.get(bob, "team-a", "sr-acl").getStatus(), "越权恢复不得改变环境状态");
    }

    @Test
    void suspendResumeAuditTrailCompleteAndSecretFree() {
        var bob = subject("bob", "team-a", Role.OWNER);
        Environment env = envService.apply(bob, request("team-a", "sr-audit", "bob",
                "v1", 300L, smallSpecs(), null));
        envService.suspend(bob, "team-a", "sr-audit");
        envService.resume(bob, "team-a", "sr-audit");
        testLog.info("输入: 对 sr-audit 依次 挂起->恢复, 检查审计轨迹");

        var records = auditRepo.findByEnv("team-a", env.getId(), 100);
        long suspendAudits = records.stream().filter(r -> r.action() == ActionType.SUSPEND).count();
        long resumeAudits = records.stream().filter(r -> r.action() == ActionType.RESUME).count();
        boolean actorRecorded = records.stream()
                .filter(r -> r.action() == ActionType.SUSPEND || r.action() == ActionType.RESUME)
                .allMatch(r -> r.actor().equals("bob"));
        boolean transitionsRecorded = records.stream().anyMatch(r ->
                r.action() == ActionType.SUSPEND
                        && r.before() == EnvironmentStatus.SUSPENDING
                        && r.after() == EnvironmentStatus.SUSPENDED)
                && records.stream().anyMatch(r ->
                r.action() == ActionType.RESUME
                        && r.before() == EnvironmentStatus.RESUMING
                        && r.after() == EnvironmentStatus.READY);
        boolean secretFree = records.stream()
                .noneMatch(r -> r.reason().contains("secret-v1"));
        testLog.info("判定依据: suspendAudits={}, resumeAudits={}, actorRecorded={}, "
                        + "transitionsRecorded={}, secretFree={}",
                suspendAudits, resumeAudits, actorRecorded, transitionsRecorded, secretFree);
        assertTrue(suspendAudits >= 2, "挂起必须留下审计（进入中间态 + 到达挂起态）");
        assertTrue(resumeAudits >= 2, "恢复必须留下审计（进入中间态 + 到达就绪态）");
        assertTrue(actorRecorded, "审计必须记录触发主体");
        assertTrue(transitionsRecorded, "审计必须记录前后状态");
        assertTrue(secretFree, "审计绝不写入敏感值");
    }

    // ------------------------------------------------------------ 失败中间态与收敛

    @Test
    void suspendBackendFailureLeavesIntermediateStateThenReconciles() {
        var bob = subject("bob", "team-a", Role.OWNER);
        Environment env = envService.apply(bob, request("team-a", "sr-fail", "bob",
                "v1", 300L, smallSpecs(), null));
        backend.failNextReleaseOnce(); // 注入：挂起释放资源时失败一次
        testLog.info("输入: sr-fail 挂起时后端释放失败一次");

        assertThrows(BackendFailureException.class,
                () -> envService.suspend(bob, "team-a", "sr-fail"),
                "底层资源操作失败必须抛出，不得返回成功假象");
        Environment mid = envService.get(bob, "team-a", "sr-fail");
        testLog.info("判定依据(中间态): status={}, usedEnvs={}, failureReason={}",
                mid.getStatus(), quotaService.getQuota("team-a").getUsedEnvs(),
                mid.getFailureReason());
        assertEquals(EnvironmentStatus.SUSPENDING, mid.getStatus(),
                "失败必须留下可查询的 SUSPENDING 中间态");
        assertEquals(1, quotaService.getQuota("team-a").getUsedEnvs(),
                "资源未释放完之前配额必须继续持有（无半更新）");

        // 重启收敛：完成挂起
        var report = reconciler.reconcile(50, 1_000);
        Environment after = envService.get(bob, "team-a", "sr-fail");
        boolean reconciled = auditRepo.findByEnv("team-a", env.getId(), 100).stream()
                .anyMatch(r -> r.action() == ActionType.RECONCILE
                        && r.after() == EnvironmentStatus.SUSPENDED);
        testLog.info("判定依据(收敛后): status={}, usedEnvs={}, backendSize={}, reconciled={}, report={}",
                after.getStatus(), quotaService.getQuota("team-a").getUsedEnvs(),
                backend.size(), reconciled, report.isTruncatedByLimit());
        assertEquals(EnvironmentStatus.SUSPENDED, after.getStatus(),
                "收敛必须把卡住的挂起推进到 SUSPENDED");
        assertEquals(0, quotaService.getQuota("team-a").getUsedEnvs(), "收敛后配额必须归还");
        assertEquals(0, backend.size(), "收敛后残留资源必须清理");
        assertTrue(reconciled, "收敛动作必须留审计");
    }

    @Test
    void resumeBackendFailureRollsBackToSuspended() {
        var bob = subject("bob", "team-a", Role.OWNER);
        envService.apply(bob, request("team-a", "sr-rfail", "bob", "v1", 300L, smallSpecs(), null));
        envService.suspend(bob, "team-a", "sr-rfail");
        backend.failAllocateRule(ctx -> true); // 注入：恢复重建资源时全部失败
        testLog.info("输入: sr-rfail 恢复时后端分配全部失败");

        assertThrows(BackendFailureException.class,
                () -> envService.resume(bob, "team-a", "sr-rfail"));
        Environment after = envService.get(bob, "team-a", "sr-rfail");
        testLog.info("判定依据: status={}, usedEnvs={}, backendSize={}, failureReason={}",
                after.getStatus(), quotaService.getQuota("team-a").getUsedEnvs(),
                backend.size(), after.getFailureReason());
        assertEquals(EnvironmentStatus.SUSPENDED, after.getStatus(),
                "恢复失败必须回滚到稳定的挂起态，绝不部分恢复");
        assertEquals(0, quotaService.getQuota("team-a").getUsedEnvs(),
                "恢复失败必须归还已占配额（无幽灵占用）");
        assertEquals(0, backend.size(), "恢复失败不得留下部分分配的资源");
        assertNotNull(after.getFailureReason(), "失败原因必须可查询");

        // 故障消除后重试 -> 成功
        backend.failAllocateRule(null);
        Environment retried = envService.resume(bob, "team-a", "sr-rfail");
        assertEquals(EnvironmentStatus.READY, retried.getStatus(), "故障消除后重试必须成功");
        assertEquals(1, quotaService.getQuota("team-a").getUsedEnvs());
    }

    @Test
    void stuckResumingIsRolledBackByReconciler() {
        var bob = subject("bob", "team-a", Role.OWNER);
        envService.apply(bob, request("team-a", "sr-crash", "bob", "v1", 300L, smallSpecs(), null));
        envService.suspend(bob, "team-a", "sr-crash");
        // 模拟恢复过程中崩溃：后端抛出非受控异常，escape 出 resume 的回滚路径，
        // 环境停留在 RESUMING 且配额已被占用（等价于进程崩溃前的落库状态）。
        backend.failAllocateRule(ctx -> {
            throw new IllegalStateException("simulated crash during resume");
        });
        testLog.info("输入: sr-crash 恢复过程中模拟崩溃（RESUMING 卡住）");

        assertThrows(IllegalStateException.class,
                () -> envService.resume(bob, "team-a", "sr-crash"));
        Environment stuck = envService.get(bob, "team-a", "sr-crash");
        testLog.info("判定依据(卡住): status={}, usedEnvs={}",
                stuck.getStatus(), quotaService.getQuota("team-a").getUsedEnvs());
        assertEquals(EnvironmentStatus.RESUMING, stuck.getStatus());
        assertEquals(1, quotaService.getQuota("team-a").getUsedEnvs(),
                "崩溃瞬间配额已占用，等待收敛归还");

        backend.failAllocateRule(null);
        reconciler.reconcile(50, 1_000);
        Environment after = envService.get(bob, "team-a", "sr-crash");
        testLog.info("判定依据(收敛后): status={}, usedEnvs={}, backendSize={}",
                after.getStatus(), quotaService.getQuota("team-a").getUsedEnvs(), backend.size());
        assertEquals(EnvironmentStatus.SUSPENDED, after.getStatus(),
                "收敛必须把卡住的恢复回滚到 SUSPENDED（可重试），不得误删环境");
        assertEquals(0, quotaService.getQuota("team-a").getUsedEnvs(),
                "收敛必须归还崩溃前占用的配额");
        assertEquals(0, backend.size(), "收敛必须清理部分分配的资源");

        // 收敛后可正常重试恢复
        Environment retried = envService.resume(bob, "team-a", "sr-crash");
        assertEquals(EnvironmentStatus.READY, retried.getStatus());
    }

    @Test
    void reconcilerNeverTouchesValidSuspendedEnvironment() {
        var bob = subject("bob", "team-a", Role.OWNER);
        Environment env = envService.apply(bob, request("team-a", "sr-safe", "bob",
                "v1", 300L, smallSpecs(), null));
        envService.suspend(bob, "team-a", "sr-safe");
        long expiryBefore = env.getExpiresAtMillis();
        testLog.info("输入: sr-safe 正常挂起(无残留), 运行收敛器");

        reconciler.reconcile(50, 1_000);
        Environment after = envService.get(bob, "team-a", "sr-safe");
        testLog.info("判定依据: status={}, expiresAt={}->{}, owner={}, templateVersion={}",
                after.getStatus(), expiryBefore, after.getExpiresAtMillis(),
                after.getOwner(), after.getTemplateVersion());
        assertEquals(EnvironmentStatus.SUSPENDED, after.getStatus(),
                "收敛器不得误改仍然有效的挂起环境");
        assertEquals(expiryBefore, after.getExpiresAtMillis(), "收敛器不得改动有效期");
        assertEquals("bob", after.getOwner(), "收敛器不得改动归属");
        assertEquals("v1", after.getTemplateVersion(), "收敛器不得改动固化配置");
    }
}
