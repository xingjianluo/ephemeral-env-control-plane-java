package com.github.xingjianluo.ephemeralenvcontrolplanejava.tests;

import com.github.xingjianluo.ephemeralenvcontrolplanejava.error.InvalidStateException;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.model.Environment;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.model.EnvironmentStatus;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.model.Role;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 续期 vs 到期回收并发：
 * - 未到期时同时发起续期与扫描 -> 只能续期成功，环境保持 READY，不得被回收；
 * - 已到期时同时发起续期与扫描 -> 只能回收成功，续期必须失败，不得出现半更新。
 */
class RenewExpiryConcurrencyTest extends AbstractControlPlaneTest {

    @Test
    void renewAndExpiryRaceBeforeDeadlineRenewalWins() throws Exception {
        var bob = subject("bob", "team-a", Role.OWNER);
        Environment env = envService.apply(bob, request("team-a", "race-ok", "bob",
                "v1", 60L, smallSpecs(), null));
        clock.advance(59_000); // 距离到期还有 1s：续期与扫描同时发生

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<Throwable> unexpected = new AtomicReference<>();
        AtomicReference<Environment> renewed = new AtomicReference<>();
        int[] expiredCount = {0};

        testLog.info("TEST race pre-expiry: {} 线程混合续期/扫描 (now < expiry)", threads);
        for (int i = 0; i < threads; i++) {
            boolean doRenew = i % 2 == 0;
            pool.submit(() -> {
                try {
                    start.await();
                    if (doRenew) {
                        Environment r = envService.renew(bob, "team-a", "race-ok", 60L);
                        renewed.set(r);
                    } else {
                        if (envService.expireIfDue(env)) {
                            expiredCount[0]++;
                        }
                    }
                } catch (Throwable t) {
                    // 多个续期并发本身允许；若出现“既续期又回收”的非法组合才记录失败。
                    unexpected.compareAndSet(null, t);
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(20, TimeUnit.SECONDS));

        Environment finalEnv = envService.get(bob, "team-a", "race-ok");
        testLog.info("判定依据: finalStatus={}, expiredWins={}, expiry={}, now={}",
                finalEnv.getStatus(), expiredCount[0], finalEnv.getExpiresAtMillis(),
                clock.instant().toEpochMilli());
        assertEquals(0, expiredCount[0], "未到期扫描绝不能回收环境");
        assertEquals(EnvironmentStatus.READY, finalEnv.getStatus(),
                "续期与扫描竞争在到期前，结果必须是 READY（续期赢），不能出现半更新");
        assertTrue(finalEnv.getExpiresAtMillis() > clock.instant().toEpochMilli(),
                "续期成功后到期时间必须被推后");
        assertEquals(1, backend.size(), "环境仍然存活，资源不得被释放");
        // 并发续期抛 InvalidState（恰好过期）以外的异常才算问题；本场景不应有任何过期异常。
        if (unexpected.get() != null) {
            fail("unexpected exception in pre-expiry race: " + unexpected.get());
        }
    }

    @Test
    void renewAndExpiryRaceAfterDeadlineExpiryWins() throws Exception {
        var bob = subject("bob", "team-a", Role.OWNER);
        Environment env = envService.apply(bob, request("team-a", "race-gone", "bob",
                "v1", 60L, smallSpecs(), null));
        clock.advance(60_000); // 已到期（now >= expiresAt）

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<Throwable> renewFailure = new AtomicReference<>();
        int[] expiredCount = {0};
        int[] skipped = {0};

        testLog.info("TEST race post-expiry: {} 线程混合续期/扫描 (now >= expiry)", threads);
        for (int i = 0; i < threads; i++) {
            boolean doRenew = i % 2 == 0;
            pool.submit(() -> {
                try {
                    start.await();
                    if (doRenew) {
                        envService.renew(bob, "team-a", "race-gone", 60L);
                    } else {
                        if (envService.expireIfDue(env)) {
                            expiredCount[0]++;
                        } else {
                            skipped[0]++;
                        }
                    }
                } catch (InvalidStateException e) {
                    renewFailure.compareAndSet(null, e); // 续期方必须看到“已过期回收”
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(20, TimeUnit.SECONDS));

        Environment finalEnv = envService.get(bob, "team-a", "race-gone");
        // 不变量：无论由扫描线程还是“续期时发现已过期”的线程执行，回收都恰好发生一次。
        long releaseAudits = auditRepo.findByEnv("team-a", env.getId(), 100).stream()
                .filter(r -> r.after() == EnvironmentStatus.RELEASED)
                .count();
        testLog.info("判定依据: finalStatus={}, scannerExpired={}, renewFailurePresent={}, releaseAudits={}",
                finalEnv.getStatus(), expiredCount[0], renewFailure.get() != null, releaseAudits);
        assertEquals(EnvironmentStatus.RELEASED, finalEnv.getStatus(),
                "到期竞争结果必须是 RELEASED（回收赢），续期不得复活环境");
        assertEquals(1, releaseAudits, "回收只能发生一次（其余线程看到终态跳过）");
        assertNotNull(renewFailure.get(), "并发续期必须失败而不是静默成功");
        assertEquals(0, backend.size(), "回收后资源必须全部释放");
        assertEquals(0, quotaService.getQuota("team-a").getUsedEnvs(), "配额必须归还");
    }
}
