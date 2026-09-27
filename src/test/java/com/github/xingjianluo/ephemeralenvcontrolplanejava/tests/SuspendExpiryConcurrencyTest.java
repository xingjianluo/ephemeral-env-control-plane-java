package com.github.xingjianluo.ephemeralenvcontrolplanejava.tests;

import com.github.xingjianluo.ephemeralenvcontrolplanejava.error.BackendFailureException;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.error.InvalidStateException;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.model.Environment;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.model.EnvironmentStatus;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.model.Role;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 挂起 vs 有效期/到期回收的确定性：
 *
 * 1) 环境已过期但扫描器尚未扫到 -> 发起挂起必须被明确拒绝（先完成回收），
 *    绝不能“挂起成功、随后又被回收”；
 * 2) 挂起与到期回收在同一环境上并发 -> 结果唯一：
 *    到期前只能挂起成功（SUSPENDED 且本轮不被回收），
 *    到期后只能回收成功（RELEASED，挂起被拒绝），回收恰好一次，无资源/配额半更新；
 * 3) 挂起到一半失败（SUSPENDING）期间到期 -> 重启收敛确定性转为回收，
 *    不误收敛成 SUSPENDED。
 */
class SuspendExpiryConcurrencyTest extends AbstractControlPlaneTest {

    /** 场景一：已过期但还没被扫描到时发起挂起 -> 明确拒绝且环境已回收。 */
    @Test
    void suspendAfterExpiryIsRejectedAndEnvironmentCollected() {
        var bob = subject("bob", "team-a", Role.OWNER);
        Environment env = envService.apply(bob, request("team-a", "expired-suspend", "bob",
                "v1", 60L, smallSpecs(), null));
        clock.advance(60_000); // now >= expiresAt，但尚未执行任何扫描
        assertEquals(EnvironmentStatus.READY,
                envService.get(bob, "team-a", "expired-suspend").getStatus(),
                "前置条件：扫描还没跑，环境在仓库里看起来仍是 READY");

        testLog.info("TEST input: 已过期未扫描的 READY 环境直接发起挂起");
        InvalidStateException ex = assertThrows(InvalidStateException.class,
                () -> envService.suspend(bob, "team-a", "expired-suspend", "too late"));
        Environment after = envService.get(bob, "team-a", "expired-suspend");
        testLog.info("判定依据: suspend 拒绝 reason='{}', finalStatus={}", ex.getMessage(), after.getStatus());

        assertEquals(EnvironmentStatus.RELEASED, after.getStatus(),
                "对已过期环境挂起：到期判定与回收在同一临界区完成，结论必须是已回收而不是挂起成功");
        assertEquals(0, backend.size(), "回收后后端资源必须全部释放");
        assertEquals(0, quotaService.getQuota("team-a").getUsedEnvs(), "回收后配额必须归还");

        // 随后扫描器再扫到它也只能看到终态跳过，不会重复回收，更不会改成 SUSPENDED。
        int expired = scanner.scanOnce(50, 5_000);
        assertEquals(0, expired, "已在挂起临界区内回收的环境不得被扫描器重复处理");
        assertEquals(EnvironmentStatus.RELEASED,
                envService.get(bob, "team-a", "expired-suspend").getStatus());
    }

    /** 场景二（到期后）：挂起与扫描并发 -> 只能回收赢，挂起被明确拒绝，回收恰好一次。 */
    @Test
    void suspendAndExpiryRaceAfterDeadlineExpiryWins() throws Exception {
        var bob = subject("bob", "team-a", Role.OWNER);
        Environment env = envService.apply(bob, request("team-a", "race-suspend-gone", "bob",
                "v1", 60L, smallSpecs(), null));
        clock.advance(60_000); // 已到期：挂起与扫描同时发生

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger suspendRejected = new AtomicInteger();
        AtomicInteger expiredCount = new AtomicInteger();
        AtomicReference<Throwable> unexpected = new AtomicReference<>();

        testLog.info("TEST race post-expiry: {} 线程混合挂起/扫描 (now >= expiry)", threads);
        for (int i = 0; i < threads; i++) {
            boolean doSuspend = i % 2 == 0;
            pool.submit(() -> {
                try {
                    start.await();
                    if (doSuspend) {
                        envService.suspend(bob, "team-a", "race-suspend-gone", "race");
                    } else if (envService.expireIfDue(env)) {
                        expiredCount.incrementAndGet();
                    }
                } catch (InvalidStateException e) {
                    suspendRejected.incrementAndGet(); // 挂起方必须看到“已过期回收”
                } catch (Throwable t) {
                    unexpected.compareAndSet(null, t);
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(20, TimeUnit.SECONDS));

        Environment after = envService.get(bob, "team-a", "race-suspend-gone");
        long releaseAudits = auditRepo.findByEnv("team-a", env.getId(), 100).stream()
                .filter(r -> r.after() == EnvironmentStatus.RELEASED).count();
        testLog.info("判定依据: finalStatus={}, scannerExpired={}, suspendRejected={}, releaseAudits={}",
                after.getStatus(), expiredCount.get(), suspendRejected.get(), releaseAudits);

        assertEquals(EnvironmentStatus.RELEASED, after.getStatus(),
                "到期竞争结果必须是 RELEASED（回收赢）：不允许挂起成功的同时又被回收");
        assertEquals(1, releaseAudits, "回收只能发生一次（其余线程看到终态跳过）");
        assertTrue(suspendRejected.get() > 0, "并发挂起必须被明确拒绝而不是静默成功");
        assertNull(unexpected.get(), "不允许出现预期外异常（半更新信号）");
        assertEquals(0, backend.size(), "不允许资源残留");
        assertEquals(0, quotaService.getQuota("team-a").getUsedEnvs(),
                "不允许“资源已释放但配额还占着”或反过来的半更新");
    }

    /** 场景二（到期前）：挂起与扫描并发 -> 只能挂起成功，扫描跳过，环境保持 SUSPENDED。 */
    @Test
    void suspendAndExpiryRaceBeforeDeadlineSuspensionWins() throws Exception {
        var bob = subject("bob", "team-a", Role.OWNER);
        Environment env = envService.apply(bob, request("team-a", "race-suspend-ok", "bob",
                "v1", 60L, smallSpecs(), null));
        clock.advance(30_000); // 未到期：挂起与扫描同时发生

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger expiredCount = new AtomicInteger();
        AtomicReference<Throwable> unexpected = new AtomicReference<>();

        testLog.info("TEST race pre-expiry: {} 线程混合挂起/扫描 (now < expiry)", threads);
        for (int i = 0; i < threads; i++) {
            boolean doSuspend = i % 2 == 0;
            pool.submit(() -> {
                try {
                    start.await();
                    if (doSuspend) {
                        envService.suspend(bob, "team-a", "race-suspend-ok", "race");
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

        Environment after = envService.get(bob, "team-a", "race-suspend-ok");
        testLog.info("判定依据: finalStatus={}, scannerExpired={}, usedEnvs={}, backendSize={}",
                after.getStatus(), expiredCount.get(),
                quotaService.getQuota("team-a").getUsedEnvs(), backend.size());

        assertEquals(0, expiredCount.get(), "未到期扫描绝不能回收环境");
        assertEquals(EnvironmentStatus.SUSPENDED, after.getStatus(),
                "到期前竞争结果必须是挂起成立（SUSPENDED），扫描在锁内看到未到期而跳过");
        assertEquals(0, backend.size(), "挂起成立必须已让出全部计算资源");
        assertEquals(0, quotaService.getQuota("team-a").getUsedEnvs(), "挂起成立必须已归还配额");
        assertNull(unexpected.get());
    }

    /** 场景三：挂起到一半失败停在 SUSPENDING，期间到期；重启收敛确定性回收。 */
    @Test
    void suspendingEnvironmentExpiredIsReconciledToRelease() {
        var bob = subject("bob", "team-a", Role.OWNER);
        Environment env = envService.apply(bob, request("team-a", "suspending-expire", "bob",
                "v1", 60L, smallSpecs(), null));
        backend.failNextReleaseOnce(); // 挂起释放中途失败 -> SUSPENDING
        assertThrows(BackendFailureException.class,
                () -> envService.suspend(bob, "team-a", "suspending-expire", "will fail"));
        assertEquals(EnvironmentStatus.SUSPENDING,
                envService.get(bob, "team-a", "suspending-expire").getStatus());
        clock.advance(60_000); // 卡住期间到期
        testLog.info("TEST input: SUSPENDING 中间态已到期，执行重启收敛");

        reconciler.reconcile(50, 5_000);
        Environment after = envService.get(bob, "team-a", "suspending-expire");
        testLog.info("判定依据: 收敛后 status={}, usedEnvs={}, backendSize={}",
                after.getStatus(), quotaService.getQuota("team-a").getUsedEnvs(), backend.size());

        assertEquals(EnvironmentStatus.RELEASED, after.getStatus(),
                "挂起途中到期的环境必须被确定性回收，不允许收敛成 SUSPENDED 后又被回收");
        assertEquals(0, backend.size());
        assertEquals(0, quotaService.getQuota("team-a").getUsedEnvs(),
                "回收路径必须资源与配额同时收敛，不允许半更新");
        assertTrue(auditRepo.findByEnv("team-a", env.getId(), 100).stream()
                .anyMatch(r -> r.action() == com.github.xingjianluo.ephemeralenvcontrolplanejava.model.ActionType.EXPIRE
                        && r.after() == EnvironmentStatus.RELEASED));
    }
}
