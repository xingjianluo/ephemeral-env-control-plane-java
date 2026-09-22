package com.github.xingjianluo.ephemeralenvcontrolplanejava.tests;

import com.github.xingjianluo.ephemeralenvcontrolplanejava.error.QuotaExceededException;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.model.EnvironmentStatus;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.model.Role;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.web.dto.ApplyRequest;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** 配额在高并发申请下不得超额分配；销毁后配额及时归还且无幽灵占用。 */
class QuotaConcurrencyTest extends AbstractControlPlaneTest {

    @Test
    void concurrentAppliesNeverExceedQuota() throws Exception {
        int threads = 24;
        var bob = subject("bob", "team-a", Role.OWNER);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();

        testLog.info("TEST quota race: {} 并发申请, team-a 配额 maxEnvs=2, maxCpu=4", threads);
        for (int i = 0; i < threads; i++) {
            int idx = i;
            pool.submit(() -> {
                try {
                    start.await();
                    ApplyRequest req = request("team-a", "q-" + idx, "bob", "v1", 60L,
                            smallSpecs(), null);
                    envService.apply(bob, req);
                    success.incrementAndGet();
                } catch (QuotaExceededException e) {
                    rejected.incrementAndGet();
                } catch (Exception e) {
                    testLog.error("unexpected exception", e);
                    fail("unexpected: " + e);
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(20, TimeUnit.SECONDS));

        var quota = quotaService.getQuota("team-a");
        testLog.info("判定依据: success={} rejected={} usedEnvs={} usedCpu={} backendSize={}",
                success.get(), rejected.get(), quota.getUsedEnvs(), quota.getUsedCpu(),
                backend.size());
        assertEquals(2, success.get(), "只有配额允许的 2 个环境能创建成功");
        assertEquals(threads - 2, rejected.get(), "其余请求必须被明确拒绝");
        assertEquals(2, quota.getUsedEnvs());
        assertEquals(2, quota.getUsedCpu());
        assertEquals(2, backend.size(), "后端资源数必须与成功创建数一致");
    }

    @Test
    void quotaFreedAfterReleaseAllowsReplacement() {
        var bob = subject("bob", "team-a", Role.OWNER);
        envService.apply(bob, request("team-a", "first", "bob", "v1", 60L,
                smallSpecs(), null));
        envService.apply(bob, request("team-a", "second", "bob", "v1", 60L,
                smallSpecs(), null));
        assertThrows(QuotaExceededException.class, () -> envService.apply(
                bob, request("team-a", "third", "bob", "v1", 60L, smallSpecs(), null)));

        envService.release(bob, "team-a", "first", "recycle");
        EnvironmentStatus before = EnvironmentStatus.RELEASED;
        testLog.info("TEST quota return: after release first expect one slot free (used=1)");
        assertEquals(1, quotaService.getQuota("team-a").getUsedEnvs());

        EnvironmentStatus replacement = envService.apply(bob,
                request("team-a", "third", "bob", "v1", 60L, smallSpecs(), null)).getStatus();
        assertEquals(EnvironmentStatus.READY, replacement);
        assertEquals(2, quotaService.getQuota("team-a").getUsedEnvs(),
                "归还后新申请应能成功，且用量恰好回到上限");
        assertEquals(2, backend.size());
    }

    @Test
    void oversizedRequestIsRejectedWithDistinctReason() {
        var dave = subject("dave", "team-b", Role.TENANT_ADMIN);
        testLog.info("TEST oversized: request cpu=8 exceeds team-b maxCpu=4");
        QuotaExceededException ex = assertThrows(QuotaExceededException.class,
                () -> envService.apply(dave,
                        request("team-b", "huge", "dave", "v1", 60L, bigSpecs(), null)));
        assertTrue(ex.getMessage().contains("cpu quota exceeded"),
                "拒绝原因必须可区分，实际: " + ex.getMessage());
        assertEquals(0, backend.size(), "被拒请求不得分配任何资源");
        assertEquals(0, quotaService.getQuota("team-b").getUsedCpu(), "被拒请求不得占用配额");
    }
}
