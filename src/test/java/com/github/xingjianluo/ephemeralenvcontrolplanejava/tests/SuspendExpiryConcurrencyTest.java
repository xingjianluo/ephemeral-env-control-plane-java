package com.github.xingjianluo.ephemeralenvcontrolplanejava.tests;

import com.github.xingjianluo.ephemeralenvcontrolplanejava.error.BackendFailureException;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.error.InvalidStateException;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.model.ActionType;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.model.AuditRecord;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.model.Environment;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.model.EnvironmentStatus;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.model.ResourceSpec;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.model.Role;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 挂起与有效期 / 到期回收的联动语义：
 * - 环境已过期但扫描器尚未处理时发起挂起：与续期/恢复一致——先完成回收再明确拒绝，
 *   绝不出现“挂起成功、随后又被回收”；
 * - 挂起与到期回收在同一环境上并发：最终只有一个确定结果（到期方回收成功、挂起方被拒绝），
 *   资源与配额不允许半更新；
 * - 挂起失败边界：SUSPENDING 中间态可查询、可重试；卡住期间到期则由收敛确定性转为回收，
 *   重启收敛既不误删也不误改仍然有效的环境。
 */
class SuspendExpiryConcurrencyTest extends AbstractControlPlaneTest {

    @Test
    void suspendExpiredReadyEnvironmentBeforeScannerIsRejectedAndCollected() {
        var bob = subject("bob", "team-a", Role.OWNER);
        Environment env = envService.apply(bob, request("team-a", "expired-suspend", "bob",
                "v1", 60L, smallSpecs(), null));
        assertEquals(EnvironmentStatus.READY, env.getStatus());
        clock.advance(60_000); // 已到期，但后台扫描尚未运行
        testLog.info("TEST input: READY 环境 expired-suspend 已到期(now>=expiresAt)但未被扫描，发起挂起");

        InvalidStateException ex = assertThrows(InvalidStateException.class,
                () -> envService.suspend(bob, "team-a", "expired-suspend", "late suspend"));
        Environment after = envService.get(bob, "team-a", "expired-suspend");
        List<AuditRecord> audits = auditRepo.findByEnv("team-a", env.getId(), 100);
        testLog.info("判定依据: suspend 拒绝 reason='{}', finalStatus={}, backendSize={}, usedEnvs={}",
                ex.getMessage(), after.getStatus(), backend.size(),
                quotaService.getQuota("team-a").getUsedEnvs());

        assertEquals(EnvironmentStatus.RELEASED, after.getStatus(),
                "对已过期环境挂起必须先完成回收再拒绝：不允许挂起成功后又被到期回收");
        assertTrue(ex.getMessage().contains("already expired"),
                "拒绝原因必须明确说明环境已过期: " + ex.getMessage());
        assertEquals(0, backend.size(), "回收必须释放全部后端资源");
        assertEquals(0, quotaService.getQuota("team-a").getUsedEnvs(), "回收必须归还全部配额");
        assertTrue(audits.stream().anyMatch(r -> r.action() == ActionType.EXPIRE
                        && r.before() == EnvironmentStatus.READY
                        && r.after() == EnvironmentStatus.RELEASING),
                "到期回收必须留下 READY -> RELEASING 的 EXPIRE 审计");
        assertTrue(audits.stream().anyMatch(r -> r.action() == ActionType.EXPIRE
                        && r.after() == EnvironmentStatus.RELEASED),
                "到期回收必须留下最终 RELEASED 的 EXPIRE 审计");
        // 不应存在任何 SUSPENDING/SUSPENDED 痕迹：挂起从未成立。
        assertFalse(audits.stream().anyMatch(r -> r.after() == EnvironmentStatus.SUSPENDING
                || r.after() == EnvironmentStatus.SUSPENDED),
                "已过期环境的挂起不得产生任何挂起状态痕迹");
    }

    @Test
    void suspendAndExpiryRaceHasDeterministicOutcome() throws Exception {
        var bob = subject("bob", "team-a", Role.OWNER);
        Environment env = envService.apply(bob, request("team-a", "race-suspend", "bob",
                "v1", 60L, smallSpecs(), null));
        clock.advance(60_000); // 已到期：挂起请求与到期扫描同时触发

        int threads = 12;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger expiredWins = new AtomicInteger();
        AtomicInteger suspendRejected = new AtomicInteger();
        AtomicInteger suspendSucceeded = new AtomicInteger();
        AtomicReference<Throwable> unexpected = new AtomicReference<>();
        testLog.info("TEST race: {} 线程混合挂起/到期扫描 (READY 环境已到期)", threads);
        for (int i = 0; i < threads; i++) {
            boolean doSuspend = i % 2 == 0;
            pool.submit(() -> {
                try {
                    start.await();
                    if (doSuspend) {
                        Environment r = envService.suspend(bob, "team-a", "race-suspend", "race suspend");
                        if (r.getStatus() == EnvironmentStatus.SUSPENDED) {
                            suspendSucceeded.incrementAndGet();
                        }
                    } else if (envService.expireIfDue(env)) {
                        expiredWins.incrementAndGet();
                    }
                } catch (InvalidStateException e) {
                    suspendRejected.incrementAndGet(); // 挂起方必须看到“已过期回收/状态不允许”
                } catch (Throwable t) {
                    unexpected.compareAndSet(null, t);
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(20, TimeUnit.SECONDS));

        Environment after = envService.get(bob, "team-a", "race-suspend");
        long releaseAudits = auditRepo.findByEnv("team-a", env.getId(), 100).stream()
                .filter(r -> r.after() == EnvironmentStatus.RELEASED).count();
        testLog.info("判定依据: finalStatus={}, expiredWins={}, suspendSucceeded={}, "
                        + "suspendRejected={}, releaseAudits={}, backendSize={}, usedEnvs={}",
                after.getStatus(), expiredWins.get(), suspendSucceeded.get(),
                suspendRejected.get(), releaseAudits, backend.size(),
                quotaService.getQuota("team-a").getUsedEnvs());

        assertEquals(0, suspendSucceeded.get(),
                "已到期环境上绝不允许挂起成功：不允许“一边挂起成功、一边被回收”");
        assertEquals(EnvironmentStatus.RELEASED, after.getStatus(),
                "到期竞争的唯一确定结果是 RELEASED：挂起方全部被明确拒绝");
        // 回收可能由扫描线程完成，也可能由抢锁后先完成回收的挂起线程完成，
        // 但整个系统内回收只能发生一次（幂等 + 同租户锁串行）。
        assertEquals(1, releaseAudits, "回收恰好发生一次");
        assertTrue(expiredWins.get() <= 1, "扫描器至多完成一次回收");
        assertTrue(suspendRejected.get() > 0, "并发挂起必须全部被明确拒绝");
        assertNull(unexpected.get(), "不允许出现预期外异常（半更新信号）");
        assertEquals(0, backend.size(), "资源必须全部释放，不允许半更新");
        assertEquals(0, quotaService.getQuota("team-a").getUsedEnvs(),
                "配额必须全部归还：不允许“资源已让出、配额还被占着”或反过来");
    }

    @Test
    void suspendFailureLeavesIntermediateAndExpiryDuringSuspendingReconcilesToRelease() {
        var bob = subject("bob", "team-a", Role.OWNER);
        // 两个资源规格，便于制造“释放到一半失败”的 SUSPENDING 残留。
        List<ResourceSpec> twoSpecs = List.of(
                new ResourceSpec("service", "app:1.0", 1, 256),
                new ResourceSpec("worker", "app:1.0", 1, 256));
        Environment env = envService.apply(bob, request("team-a", "suspending-expire", "bob",
                "v1", 60L, twoSpecs, null));
        assertEquals(2, backend.size());
        backend.failNextReleaseOnce(); // releaseAll 删掉第一个资源后抛异常
        testLog.info("TEST input: 挂起释放中途失败 -> SUSPENDING 中间态（1 个残留资源，配额仍占用）");

        assertThrows(BackendFailureException.class,
                () -> envService.suspend(bob, "team-a", "suspending-expire", "will fail"));
        Environment mid = envService.get(bob, "team-a", "suspending-expire");
        assertEquals(EnvironmentStatus.SUSPENDING, mid.getStatus(),
                "资源释放中途失败必须停留可查询、可重试的 SUSPENDING 中间态，不返回成功假象");
        assertNotNull(mid.getFailureReason());
        assertEquals(1, backend.size(), "仅一个资源被释放，另一个残留");
        assertEquals(1, quotaService.getQuota("team-a").getUsedEnvs(),
                "资源没释放干净时配额不得提前归还");
        assertEquals(2, quotaService.getQuota("team-a").getUsedCpu(),
                "资源没释放干净时 CPU 配额不得提前归还");

        clock.advance(60_000); // 卡在 SUSPENDING 期间租约到期
        testLog.info("TEST input: SUSPENDING 环境已到期，执行重启收敛");

        var report = reconciler.reconcile(50, 5_000);
        Environment after = envService.get(bob, "team-a", "suspending-expire");
        testLog.info("判定依据: finalStatus={}, backendSize={}, usedEnvs={}, report={}",
                after.getStatus(), backend.size(),
                quotaService.getQuota("team-a").getUsedEnvs(),
                report.getStuckEnvironmentsRecovered());
        assertEquals(EnvironmentStatus.RELEASED, after.getStatus(),
                "挂起途中到期必须被确定性地收敛为回收，不允许先 SUSPENDED 又被回收");
        assertEquals(0, backend.size(), "残留资源必须全部清掉");
        assertEquals(0, quotaService.getQuota("team-a").getUsedEnvs(), "配额必须归还，不允许半更新");
        assertEquals(0, quotaService.getQuota("team-a").getUsedCpu());
        assertTrue(auditRepo.findByEnv("team-a", env.getId(), 100).stream()
                        .anyMatch(r -> r.action() == ActionType.EXPIRE
                                && r.after() == EnvironmentStatus.RELEASED),
                "必须留下到期回收审计");
    }

    @Test
    void idempotentSuspendOfExpiredSuspendedEnvironmentIsRejectedAndCollected() {
        var bob = subject("bob", "team-a", Role.OWNER);
        Environment env = envService.apply(bob, request("team-a", "expired-suspended-again", "bob",
                "v1", 60L, smallSpecs(), null));
        envService.suspend(bob, "team-a", "expired-suspended-again", "idle");
        clock.advance(60_000); // SUSPENDED 已到期，扫描尚未运行
        testLog.info("TEST input: 已到期的 SUSPENDED 环境再次发起挂起（幂等路径）");

        InvalidStateException ex = assertThrows(InvalidStateException.class,
                () -> envService.suspend(bob, "team-a", "expired-suspended-again", "again"));
        Environment after = envService.get(bob, "team-a", "expired-suspended-again");
        testLog.info("判定依据: reject reason='{}', finalStatus={}, backendSize={}",
                ex.getMessage(), after.getStatus(), backend.size());
        assertEquals(EnvironmentStatus.RELEASED, after.getStatus(),
                "幂等挂起路径也不允许成功假象：已到期 SUSPENDED 必须先回收再拒绝");
        assertEquals(0, backend.size());
        assertEquals(0, quotaService.getQuota("team-a").getUsedEnvs());
        assertTrue(auditRepo.findByEnv("team-a", env.getId(), 100).stream()
                .anyMatch(r -> r.action() == ActionType.EXPIRE
                        && r.after() == EnvironmentStatus.RELEASED));
    }

    @Test
    void stuckSuspendingBeforeExpiryReconcilesToSuspendedWithoutTouchingValidEnvs() {
        var bob = subject("bob", "team-a", Role.OWNER);
        // 一个挂起失败的环境（未到期，卡在 SUSPENDING）+ 一个完全有效的 READY 环境。
        Environment stuck = envService.apply(bob, request("team-a", "stuck-ok", "bob",
                "v1", 300L, smallSpecs(), null));
        Environment valid = envService.apply(bob, request("team-a", "valid-ready", "bob",
                "v1", 300L, smallSpecs(), null));
        long validExpiryBefore = valid.getExpiresAtMillis();
        backend.failNextReleaseOnce();
        assertThrows(BackendFailureException.class,
                () -> envService.suspend(bob, "team-a", "stuck-ok", "will fail"));
        assertEquals(EnvironmentStatus.SUSPENDING,
                envService.get(bob, "team-a", "stuck-ok").getStatus());
        testLog.info("TEST input: 未到期 SUSPENDING 卡住 + 有效 READY 同场收敛");

        var report = reconciler.reconcile(50, 5_000);
        Environment stuckAfter = envService.get(bob, "team-a", "stuck-ok");
        Environment validAfter = envService.get(bob, "team-a", "valid-ready");
        testLog.info("判定依据: stuck -> {}, valid stays {}, backendSize={}, usedEnvs={}",
                stuckAfter.getStatus(), validAfter.getStatus(), backend.size(),
                quotaService.getQuota("team-a").getUsedEnvs());
        assertEquals(EnvironmentStatus.SUSPENDED, stuckAfter.getStatus(),
                "未到期的 SUSPENDING 必须继续收敛为 SUSPENDED（可重试成功），不能误回收");
        assertEquals(EnvironmentStatus.READY, validAfter.getStatus(),
                "收敛绝不能误改仍然有效的 READY 环境");
        assertEquals(validExpiryBefore, validAfter.getExpiresAtMillis(),
                "有效环境的到期时间不得被动");
        // SUSPENDED 0 个后端资源 + READY 1 个后端资源。
        assertEquals(1, backend.size());
        assertEquals(1, quotaService.getQuota("team-a").getUsedEnvs(),
                "挂起完成归还配额、有效 READY 仍占配额，资源与配额一致");
        assertTrue(report.getStuckEnvironmentsRecovered() >= 1);
    }
}
