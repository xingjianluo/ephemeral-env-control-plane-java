package com.github.xingjianluo.ephemeralenvcontrolplanejava.tests;

import com.github.xingjianluo.ephemeralenvcontrolplanejava.error.BackendFailureException;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.error.ForbiddenException;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.error.InvalidStateException;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.error.QuotaExceededException;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.model.ActionType;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.model.AuditRecord;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.model.Environment;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.model.EnvironmentStatus;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.model.Role;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 挂起 / 恢复语义：
 * - 挂起让出计算资源与配额，但保留身份、归属与固化配置快照；恢复后回到可访问状态；
 * - 挂起/恢复幂等；挂起后注册的模板新版本不追溯影响；
 * - 挂起不免回收：到期与最长保留时间都会被扫描清理；
 * - 恢复需重新占用配额，配额竞争失败时明确拒绝且环境稳定停在 SUSPENDED；
 * - 越权拒绝、审计完整且不含敏感值；后端失败留可查询中间态并由收敛器推进。
 */
class SuspendResumeTest extends AbstractControlPlaneTest {

    @Test
    void suspendReleasesComputeAndResumeRestoresWithPinnedConfig() {
        var bob = subject("bob", "team-a", Role.OWNER);
        Environment env = envService.apply(bob, request("team-a", "idle-env", "bob",
                "v1", 300L, smallSpecs(), Map.of("replicas", 2)));
        assertEquals(EnvironmentStatus.READY, env.getStatus());
        Map<String, Object> snapshotBefore = env.getConfigValues();
        testLog.info("TEST input: 挂起 READY 环境 idle-env (quota used={}/{})",
                quotaService.getQuota("team-a").getUsedEnvs(),
                quotaService.getQuota("team-a").getUsedCpu());

        Environment suspended = envService.suspend(bob, "team-a", "idle-env", "idle after sprint");
        testLog.info("判定依据: status={}, backendSize={}, usedEnvs={}, usedCpu={}, usedMem={}",
                suspended.getStatus(), backend.size(),
                quotaService.getQuota("team-a").getUsedEnvs(),
                quotaService.getQuota("team-a").getUsedCpu(),
                quotaService.getQuota("team-a").getUsedMemoryMb());
        assertEquals(EnvironmentStatus.SUSPENDED, suspended.getStatus());
        assertEquals(0, backend.size(), "挂起必须释放全部计算资源");
        assertEquals(0, quotaService.getQuota("team-a").getUsedEnvs(), "挂起必须归还环境数配额");
        assertEquals(0, quotaService.getQuota("team-a").getUsedCpu(), "挂起必须归还 CPU 配额");
        assertEquals(0, quotaService.getQuota("team-a").getUsedMemoryMb(), "挂起必须归还内存配额");
        // 身份、归属、配置快照保持不变，仍可查询。
        assertEquals("bob", suspended.getOwner());
        assertEquals("v1", suspended.getTemplateVersion());
        assertEquals(snapshotBefore, suspended.getConfigValues());
        assertTrue(suspended.getSuspendedAtMillis() > 0);

        // 幂等：重复挂起不报错、不重复释放。
        Environment again = envService.suspend(bob, "team-a", "idle-env", "duplicate suspend");
        assertEquals(EnvironmentStatus.SUSPENDED, again.getStatus());
        assertEquals(0, backend.size());
        assertEquals(0, quotaService.getQuota("team-a").getUsedEnvs());

        // 挂起之后注册的新模板版本不得追溯改变已固化的配置。
        templates.register("v3", "dbPassword",
                Map.of("image", "app:1.0", "replicas", 1, "dbPassword", "secret-v1",
                        "featureFlag", false, "newField", "new-default"));

        Environment resumed = envService.resume(bob, "team-a", "idle-env");
        testLog.info("判定依据: resumed status={}, templateVersion={}, config={}, usedEnvs={}",
                resumed.getStatus(), resumed.getTemplateVersion(), resumed.getConfigValues(),
                quotaService.getQuota("team-a").getUsedEnvs());
        assertEquals(EnvironmentStatus.READY, resumed.getStatus());
        assertEquals(1, backend.size(), "恢复必须重建后端资源");
        assertEquals(1, quotaService.getQuota("team-a").getUsedEnvs(), "恢复必须重新占用配额");
        assertEquals("v1", resumed.getTemplateVersion(), "模板版本必须保持挂起前固化值");
        assertEquals(snapshotBefore, resumed.getConfigValues(),
                "配置快照必须保持挂起前状态，新版本模板不追溯影响");
        assertFalse(resumed.getConfigValues().containsKey("newField"));

        // 幂等：重复恢复不报错、不重复占用配额/资源。
        Environment resumedAgain = envService.resume(bob, "team-a", "idle-env");
        assertEquals(EnvironmentStatus.READY, resumedAgain.getStatus());
        assertEquals(1, backend.size());
        assertEquals(1, quotaService.getQuota("team-a").getUsedEnvs());
    }

    @Test
    void suspendFreesQuotaForOthersAndResumeLosesContentionSafely() {
        var bob = subject("bob", "team-a", Role.OWNER);
        var alice = subject("alice", "team-a", Role.TENANT_ADMIN);
        envService.apply(bob, request("team-a", "e1", "bob", "v1", 300L, smallSpecs(), null));
        envService.apply(alice, request("team-a", "e2", "alice", "v1", 300L, smallSpecs(), null));
        assertEquals(2, quotaService.getQuota("team-a").getUsedEnvs());

        // 挂起 e1 让出配额 -> 同租户 e3 可以申请成功。
        envService.suspend(bob, "team-a", "e1", "free quota");
        envService.apply(alice, request("team-a", "e3", "alice", "v1", 300L, smallSpecs(), null));
        testLog.info("TEST input: 配额已被 e2/e3 占满 (used={})，恢复挂起的 e1",
                quotaService.getQuota("team-a").getUsedEnvs());

        QuotaExceededException ex = assertThrows(QuotaExceededException.class,
                () -> envService.resume(bob, "team-a", "e1"));
        Environment e1 = envService.get(bob, "team-a", "e1");
        testLog.info("判定依据: resume 被拒绝 reason='{}', e1 status={}, backendSize={}",
                ex.getMessage(), e1.getStatus(), backend.size());
        assertEquals(EnvironmentStatus.SUSPENDED, e1.getStatus(),
                "配额竞争失败时环境必须稳定停在 SUSPENDED 以便重试，不允许部分恢复");
        assertEquals(2, backend.size(), "失败的恢复不得留下任何后端资源");
        assertEquals(2, quotaService.getQuota("team-a").getUsedEnvs(),
                "失败的恢复不得占用配额");

        // 释放 e3 后重试恢复成功。
        envService.release(alice, "team-a", "e3", "make room");
        Environment resumed = envService.resume(bob, "team-a", "e1");
        assertEquals(EnvironmentStatus.READY, resumed.getStatus());
        assertEquals(2, quotaService.getQuota("team-a").getUsedEnvs());
        assertEquals(2, backend.size());
    }

    @Test
    void suspendedEnvironmentStillExpiresByLease() {
        var bob = subject("bob", "team-a", Role.OWNER);
        Environment env = envService.apply(bob, request("team-a", "suspend-expire", "bob",
                "v1", 60L, smallSpecs(), null));
        envService.suspend(bob, "team-a", "suspend-expire", "idle");
        clock.advance(60_000); // 挂起期间到期
        testLog.info("TEST input: 挂起环境已到期 (now >= expiresAt)，执行到期扫描");

        int expired = scanner.scanOnce(50, 5_000);
        Environment after = envService.get(bob, "team-a", "suspend-expire");
        List<AuditRecord> audits = auditRepo.findByEnv("team-a", env.getId(), 100);
        testLog.info("判定依据: expired={}, finalStatus={}, auditActions={}",
                expired, after.getStatus(), audits.stream().map(AuditRecord::action).toList());
        assertEquals(1, expired, "挂起中的环境到期必须被自动清理");
        assertEquals(EnvironmentStatus.RELEASED, after.getStatus());
        assertTrue(audits.stream().anyMatch(r -> r.action() == ActionType.EXPIRE
                        && r.reason().contains("SUSPENDED")),
                "挂起中的到期清理必须留下可查询的审计记录");
        assertEquals(0, backend.size());
        assertEquals(0, quotaService.getQuota("team-a").getUsedEnvs());
    }

    @Test
    void suspendedEnvironmentCollectedAfterMaxRetention() {
        var bob = subject("bob", "team-a", Role.OWNER);
        props.getSuspend().setMaxRetentionSeconds(120);
        Environment env = envService.apply(bob, request("team-a", "retention", "bob",
                "v1", 3600L, smallSpecs(), null)); // 租约还很长
        envService.suspend(bob, "team-a", "retention", "idle");
        clock.advance(121_000); // 超过挂起最长保留 120s，但租约远未到期
        testLog.info("TEST input: 挂起时长超过 maxRetentionSeconds=120 (租约未到期)，执行扫描");

        int expired = scanner.scanOnce(50, 5_000);
        Environment after = envService.get(bob, "team-a", "retention");
        List<AuditRecord> audits = auditRepo.findByEnv("team-a", env.getId(), 100);
        testLog.info("判定依据: expired={}, finalStatus={}, reasons={}",
                expired, after.getStatus(), audits.stream().map(AuditRecord::reason).toList());
        assertEquals(1, expired, "挂起环境不得无界堆积：超过最长保留时间必须被清理");
        assertEquals(EnvironmentStatus.RELEASED, after.getStatus());
        assertTrue(audits.stream().anyMatch(r -> r.action() == ActionType.EXPIRE
                && r.reason().contains("retention")));
    }

    @Test
    void resumeAfterExpiryIsRejectedAndEnvironmentCollected() {
        var bob = subject("bob", "team-a", Role.OWNER);
        envService.apply(bob, request("team-a", "expired-resume", "bob",
                "v1", 60L, smallSpecs(), null));
        envService.suspend(bob, "team-a", "expired-resume", "idle");
        clock.advance(60_000); // 已到期
        testLog.info("TEST input: 对已到期挂起环境发起恢复");

        InvalidStateException ex = assertThrows(InvalidStateException.class,
                () -> envService.resume(bob, "team-a", "expired-resume"));
        Environment after = envService.get(bob, "team-a", "expired-resume");
        testLog.info("判定依据: resume 拒绝 reason='{}', finalStatus={}", ex.getMessage(), after.getStatus());
        assertEquals(EnvironmentStatus.RELEASED, after.getStatus(),
                "到期判定与回收在恢复临界区内完成：恢复必须失败且环境被回收，只有一个确定结果");
        assertEquals(0, quotaService.getQuota("team-a").getUsedEnvs());
        assertEquals(0, backend.size());
    }

    @Test
    void resumeAndExpiryRaceHasDeterministicOutcome() throws Exception {
        var bob = subject("bob", "team-a", Role.OWNER);
        Environment env = envService.apply(bob, request("team-a", "race-resume", "bob",
                "v1", 60L, smallSpecs(), null));
        envService.suspend(bob, "team-a", "race-resume", "idle");
        clock.advance(60_000); // 已到期：恢复与扫描并发

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger expiredCount = new AtomicInteger();
        AtomicInteger resumeRejected = new AtomicInteger();
        AtomicReference<Throwable> unexpected = new AtomicReference<>();
        testLog.info("TEST race: {} 线程混合恢复/扫描 (挂起环境已到期)", threads);
        for (int i = 0; i < threads; i++) {
            boolean doResume = i % 2 == 0;
            pool.submit(() -> {
                try {
                    start.await();
                    if (doResume) {
                        envService.resume(bob, "team-a", "race-resume");
                    } else if (envService.expireIfDue(env)) {
                        expiredCount.incrementAndGet();
                    }
                } catch (InvalidStateException e) {
                    resumeRejected.incrementAndGet(); // 恢复方必须看到“已过期回收”
                } catch (Throwable t) {
                    unexpected.compareAndSet(null, t);
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(20, TimeUnit.SECONDS));

        Environment after = envService.get(bob, "team-a", "race-resume");
        long releaseAudits = auditRepo.findByEnv("team-a", env.getId(), 100).stream()
                .filter(r -> r.after() == EnvironmentStatus.RELEASED).count();
        testLog.info("判定依据: finalStatus={}, expiredWins={}, resumeRejected={}, releaseAudits={}",
                after.getStatus(), expiredCount.get(), resumeRejected.get(), releaseAudits);
        assertEquals(EnvironmentStatus.RELEASED, after.getStatus(),
                "到期竞争结果必须是 RELEASED：不允许恢复成功的同时又被回收");
        assertEquals(1, releaseAudits, "回收恰好发生一次");
        assertTrue(resumeRejected.get() > 0, "并发恢复必须被明确拒绝");
        assertNull(unexpected.get(), "不允许出现预期外异常（半更新信号）");
        assertEquals(0, backend.size());
        assertEquals(0, quotaService.getQuota("team-a").getUsedEnvs(),
                "不允许“资源已释放但配额还被占着”或反过来的半更新");
    }

    @Test
    void resumeBeforeExpiryWinsAgainstScanner() throws Exception {
        var bob = subject("bob", "team-a", Role.OWNER);
        Environment env = envService.apply(bob, request("team-a", "race-resume-ok", "bob",
                "v1", 60L, smallSpecs(), null));
        envService.suspend(bob, "team-a", "race-resume-ok", "idle");
        clock.advance(30_000); // 未到期

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger expiredCount = new AtomicInteger();
        AtomicReference<Throwable> unexpected = new AtomicReference<>();
        testLog.info("TEST race: {} 线程混合恢复/扫描 (挂起环境未到期)", threads);
        for (int i = 0; i < threads; i++) {
            boolean doResume = i % 2 == 0;
            pool.submit(() -> {
                try {
                    start.await();
                    if (doResume) {
                        envService.resume(bob, "team-a", "race-resume-ok");
                    } else if (envService.expireIfDue(env)) {
                        expiredCount.incrementAndGet();
                    }
                } catch (Throwable t) {
                    unexpected.compareAndSet(null, t);
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(20, TimeUnit.SECONDS));

        Environment after = envService.get(bob, "team-a", "race-resume-ok");
        testLog.info("判定依据: finalStatus={}, expiredWins={}, usedEnvs={}, backendSize={}",
                after.getStatus(), expiredCount.get(),
                quotaService.getQuota("team-a").getUsedEnvs(), backend.size());
        assertEquals(0, expiredCount.get(), "未到期扫描绝不能回收挂起环境");
        assertEquals(EnvironmentStatus.READY, after.getStatus(), "恢复必须成功");
        assertEquals(1, quotaService.getQuota("team-a").getUsedEnvs(),
                "并发重复恢复不得重复占用配额");
        assertEquals(1, backend.size(), "并发重复恢复不得重复分配资源");
        assertNull(unexpected.get());
    }

    @Test
    void suspendResumeAccessControlAndAudit() {
        var bob = subject("bob", "team-a", Role.OWNER);
        var carol = subject("carol", "team-a", Role.VIEWER);
        var dave = subject("dave", "team-b", Role.TENANT_ADMIN);
        acs.register("erin", "team-a", "OWNER");
        var erin = subject("erin", "team-a", Role.OWNER);
        Environment env = envService.apply(bob, request("team-a", "acl-env", "bob",
                "v1", 300L, smallSpecs(), null));

        testLog.info("TEST input: VIEWER/跨租户管理员/非本人 OWNER 尝试挂起与恢复");
        assertThrows(ForbiddenException.class, () -> envService.suspend(carol, "team-a", "acl-env", "x"));
        assertThrows(ForbiddenException.class, () -> envService.suspend(dave, "team-a", "acl-env", "x"));
        assertThrows(ForbiddenException.class, () -> envService.suspend(erin, "team-a", "acl-env", "x"));
        assertEquals(EnvironmentStatus.READY, envService.get(bob, "team-a", "acl-env").getStatus(),
                "越权挂起不得改变环境状态");

        envService.suspend(bob, "team-a", "acl-env", "legit suspend");
        assertThrows(ForbiddenException.class, () -> envService.resume(carol, "team-a", "acl-env"));
        assertThrows(ForbiddenException.class, () -> envService.resume(dave, "team-a", "acl-env"));
        assertThrows(ForbiddenException.class, () -> envService.resume(erin, "team-a", "acl-env"));
        assertEquals(EnvironmentStatus.SUSPENDED, envService.get(bob, "team-a", "acl-env").getStatus(),
                "越权恢复不得改变环境状态");

        // 有权者（租户管理员）可以恢复他人环境。
        var alice = subject("alice", "team-a", Role.TENANT_ADMIN);
        envService.resume(alice, "team-a", "acl-env");

        List<AuditRecord> audits = auditRepo.findByEnv("team-a", env.getId(), 100);
        testLog.info("判定依据: audit 记录={}", audits.stream()
                .map(r -> r.action() + ":" + r.actor() + ":" + r.before() + "->" + r.after()).toList());
        assertTrue(audits.stream().anyMatch(r -> r.action() == ActionType.SUSPEND
                        && r.actor().equals("bob")
                        && r.before() == EnvironmentStatus.READY
                        && r.after() == EnvironmentStatus.SUSPENDING),
                "挂起必须留下含触发主体与前后状态的审计");
        assertTrue(audits.stream().anyMatch(r -> r.action() == ActionType.SUSPEND
                && r.after() == EnvironmentStatus.SUSPENDED));
        assertTrue(audits.stream().anyMatch(r -> r.action() == ActionType.RESUME
                        && r.actor().equals("alice")
                        && r.after() == EnvironmentStatus.READY),
                "恢复必须留下含触发主体与前后状态的审计");
        // 越权尝试不产生状态变更审计（状态未变）。
        assertEquals(0, audits.stream().filter(r -> r.actor().equals("carol")
                || r.actor().equals("dave") || r.actor().equals("erin")).count());
        // 任何审计记录都不得包含敏感值。
        for (AuditRecord r : audits) {
            assertFalse(r.reason().contains("secret-v1"), "审计不得写入敏感值: " + r.reason());
        }
    }

    @Test
    void suspendBackendFailureLeavesQueryableIntermediateAndReconciles() {
        var bob = subject("bob", "team-a", Role.OWNER);
        Environment env = envService.apply(bob, request("team-a", "suspend-fail", "bob",
                "v1", 300L, smallSpecs(), null));
        backend.failNextReleaseOnce();
        testLog.info("TEST input: 挂起时注入后端释放失败");

        assertThrows(BackendFailureException.class,
                () -> envService.suspend(bob, "team-a", "suspend-fail", "will fail"));
        Environment mid = envService.get(bob, "team-a", "suspend-fail");
        testLog.info("判定依据: 中间态 status={}, failureReason={}, usedEnvs={}",
                mid.getStatus(), mid.getFailureReason(),
                quotaService.getQuota("team-a").getUsedEnvs());
        assertEquals(EnvironmentStatus.SUSPENDING, mid.getStatus(),
                "后端失败必须留下可查询的 SUSPENDING 中间态，而不是成功假象");
        assertNotNull(mid.getFailureReason());
        assertEquals(1, quotaService.getQuota("team-a").getUsedEnvs(),
                "挂起未完成前配额不得提前归还（避免配额与资源状态不一致）");

        var report = reconciler.reconcile(50, 5_000);
        Environment after = envService.get(bob, "team-a", "suspend-fail");
        testLog.info("判定依据: 收敛后 status={}, usedEnvs={}, backendSize={}, report.stuck={}",
                after.getStatus(), quotaService.getQuota("team-a").getUsedEnvs(),
                backend.size(), report.getStuckEnvironmentsRecovered());
        assertEquals(EnvironmentStatus.SUSPENDED, after.getStatus(), "收敛器必须把卡住的挂起推进到 SUSPENDED");
        assertEquals(0, backend.size());
        assertEquals(0, quotaService.getQuota("team-a").getUsedEnvs());
        assertTrue(auditRepo.findByEnv("team-a", env.getId(), 100).stream()
                .anyMatch(r -> r.action() == ActionType.RECONCILE
                        && r.after() == EnvironmentStatus.SUSPENDED));
    }

    @Test
    void resumeBackendFailureLeavesQueryableIntermediateAndReconciles() {
        var bob = subject("bob", "team-a", Role.OWNER);
        Environment env = envService.apply(bob, request("team-a", "resume-fail", "bob",
                "v1", 300L, smallSpecs(), null));
        envService.suspend(bob, "team-a", "resume-fail", "idle");
        backend.failAllocateRule(ctx -> true);
        testLog.info("TEST input: 恢复时注入后端分配失败");

        assertThrows(BackendFailureException.class, () -> envService.resume(bob, "team-a", "resume-fail"));
        Environment mid = envService.get(bob, "team-a", "resume-fail");
        testLog.info("判定依据: 中间态 status={}, failureReason={}, usedEnvs={}",
                mid.getStatus(), mid.getFailureReason(),
                quotaService.getQuota("team-a").getUsedEnvs());
        assertEquals(EnvironmentStatus.RESUMING, mid.getStatus(),
                "恢复失败必须留下可查询的 RESUMING 中间态，而不是成功假象");
        assertNotNull(mid.getFailureReason());
        assertEquals(1, quotaService.getQuota("team-a").getUsedEnvs(),
                "RESUMING 持有配额与（部分）资源，状态一致，不允许“资源在但配额未占”");

        backend.failAllocateRule(null); // 后端恢复
        reconciler.reconcile(50, 5_000);
        Environment after = envService.get(bob, "team-a", "resume-fail");
        testLog.info("判定依据: 收敛后 status={}, backendSize={}, usedEnvs={}",
                after.getStatus(), backend.size(), quotaService.getQuota("team-a").getUsedEnvs());
        assertEquals(EnvironmentStatus.READY, after.getStatus(), "收敛器必须把卡住的恢复推进到 READY");
        assertEquals(1, backend.size());
        assertEquals(1, quotaService.getQuota("team-a").getUsedEnvs());
        assertTrue(auditRepo.findByEnv("team-a", env.getId(), 100).stream()
                .anyMatch(r -> r.action() == ActionType.RECONCILE
                        && r.after() == EnvironmentStatus.READY));
    }

    @Test
    void resumingEnvironmentExpiredIsReconciledToRelease() {
        var bob = subject("bob", "team-a", Role.OWNER);
        Environment env = envService.apply(bob, request("team-a", "resuming-expire", "bob",
                "v1", 60L, smallSpecs(), null));
        envService.suspend(bob, "team-a", "resuming-expire", "idle");
        backend.failAllocateRule(ctx -> true);
        assertThrows(BackendFailureException.class,
                () -> envService.resume(bob, "team-a", "resuming-expire"));
        assertEquals(EnvironmentStatus.RESUMING,
                envService.get(bob, "team-a", "resuming-expire").getStatus());
        clock.advance(60_000); // 恢复卡住期间到期
        backend.failAllocateRule(null);
        testLog.info("TEST input: RESUMING 中间态环境已到期，执行收敛");

        reconciler.reconcile(50, 5_000);
        Environment after = envService.get(bob, "team-a", "resuming-expire");
        testLog.info("判定依据: 收敛后 status={}, usedEnvs={}, backendSize={}",
                after.getStatus(), quotaService.getQuota("team-a").getUsedEnvs(), backend.size());
        assertEquals(EnvironmentStatus.RELEASED, after.getStatus(),
                "恢复途中到期的环境必须被确定性地回收，不允许恢复成功同时又被回收");
        assertEquals(0, quotaService.getQuota("team-a").getUsedEnvs());
        assertEquals(0, backend.size());
        assertTrue(auditRepo.findByEnv("team-a", env.getId(), 100).stream()
                .anyMatch(r -> r.action() == ActionType.EXPIRE
                        && r.after() == EnvironmentStatus.RELEASED));
    }

    @Test
    void reconcilerNeverTouchesValidSuspendedEnvironmentButCleansLeakedResources() {
        var bob = subject("bob", "team-a", Role.OWNER);
        Environment env = envService.apply(bob, request("team-a", "stable-suspended", "bob",
                "v1", 300L, smallSpecs(), null));
        envService.suspend(bob, "team-a", "stable-suspended", "idle");
        long suspendedAt = env.getSuspendedAtMillis();
        long auditCount = auditRepo.findByEnv("team-a", env.getId(), 100).size();
        // 模拟崩溃残留：挂起环境名下多出一条泄漏的后端资源。
        backend.allocate(env.getId(), smallSpecs().get(0));
        testLog.info("TEST input: 有效 SUSPENDED 环境 + 1 条泄漏资源，执行收敛");

        var report = reconciler.reconcile(50, 5_000);
        Environment after = envService.get(bob, "team-a", "stable-suspended");
        testLog.info("判定依据: status={}, suspendedAt={}, backendSize={}, orphanRemoved={}",
                after.getStatus(), after.getSuspendedAtMillis(), backend.size(),
                report.getOrphanResourcesRemoved());
        assertEquals(EnvironmentStatus.SUSPENDED, after.getStatus(),
                "收敛绝不能误改仍然有效的挂起环境");
        assertEquals(suspendedAt, after.getSuspendedAtMillis());
        assertEquals(0, backend.size(), "挂起环境名下的泄漏资源必须被清理");
        assertEquals(1, report.getOrphanResourcesRemoved());
        assertEquals(auditCount, auditRepo.findByEnv("team-a", env.getId(), 100).size(),
                "对有效挂起环境不得产生误操作审计");
    }

    @Test
    void illegalSuspendResumeTransitionsAreRejected() {
        var bob = subject("bob", "team-a", Role.OWNER);
        envService.apply(bob, request("team-a", "transitions", "bob",
                "v1", 300L, smallSpecs(), null));
        testLog.info("TEST input: 对 READY 环境恢复、对 SUSPENDED 环境续期/重复挂起后回收");

        // READY 上恢复 = 幂等返回（不报错）；但未挂起过的 READY 环境恢复不应改变任何占用。
        Environment ready = envService.resume(bob, "team-a", "transitions");
        assertEquals(EnvironmentStatus.READY, ready.getStatus());
        assertEquals(1, quotaService.getQuota("team-a").getUsedEnvs());
        assertEquals(1, backend.size());

        envService.suspend(bob, "team-a", "transitions", "idle");
        // 挂起中不可续期（必须先恢复）。
        assertThrows(InvalidStateException.class,
                () -> envService.renew(bob, "team-a", "transitions", 60L));
        // 挂起环境可直接回收。
        envService.release(bob, "team-a", "transitions", "done");
        Environment released = envService.get(bob, "team-a", "transitions");
        assertEquals(EnvironmentStatus.RELEASED, released.getStatus());
        // 终态上挂起/恢复都被拒绝。
        assertThrows(InvalidStateException.class,
                () -> envService.suspend(bob, "team-a", "transitions", "x"));
        assertThrows(InvalidStateException.class,
                () -> envService.resume(bob, "team-a", "transitions"));
        assertEquals(0, quotaService.getQuota("team-a").getUsedEnvs());
        assertEquals(0, backend.size());
    }
}
