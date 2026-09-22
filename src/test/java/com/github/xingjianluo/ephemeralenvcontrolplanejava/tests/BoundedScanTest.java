package com.github.xingjianluo.ephemeralenvcontrolplanejava.tests;

import com.github.xingjianluo.ephemeralenvcontrolplanejava.error.ApiException;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.error.ErrorCode;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.*;

class BoundedScanTest {

    private static final Logger log = LoggerFactory.getLogger(BoundedScanTest.class);

    @Test
    void sweepHonoursPageSizeAndConvergesAcrossPasses() {
        TestHarness h = new TestHarness();
        h.props.setScanPageSize(2);
        h.props.setScanBudgetMillis(60_000); // page is the binding bound here
        // 5 expired envs (quota allows only 3 in alpha, so use distinct names
        // across both seeded tenants).
        h.fresh(h.alice(), "exp-1");
        h.fresh(h.alice(), "exp-2");
        h.fresh(h.alice(), "exp-3");
        h.fresh(h.dave(), "exp-4");
        h.fresh(h.dave(), "exp-5");
        h.clock.advanceSeconds(4000);

        int first = h.reaper.sweepOnce();
        int second = h.reaper.sweepOnce();
        int third = h.reaper.sweepOnce();
        int total = first + second + third;
        log.info("input 5 expired envs pageSize=2; decision passes recycled {},{},{} total={}",
                first, second, third, total);
        assertEquals(2, first, "first pass bounded by page size");
        assertEquals(5, total, "repeated passes eventually converge");
        assertTrue(h.backend.listAll().isEmpty());
    }

    @Test
    void sweepHonoursTimeBudget() {
        TestHarness h = new TestHarness();
        h.props.setScanPageSize(100);
        h.props.setScanBudgetMillis(0); // zero budget: only the first may pass
        for (int i = 0; i < 3; i++) {
            h.fresh(h.alice(), "bud-" + i);
        }
        h.clock.advanceSeconds(4000);
        int recycled = h.reaper.sweepOnce();
        log.info("input budgetMillis=0; decision recycled={} (<= page, remainder deferred)",
                recycled);
        assertTrue(recycled <= 1, "time budget caps work per pass");
    }

    @Test
    void bulkQueryOverLimitIsRejected() {
        TestHarness h = new TestHarness();
        h.props.setBulkQueryLimit(10);
        h.fresh(h.alice(), "listed");
        ApiException ex = assertThrows(ApiException.class,
                () -> h.envs.list("alice", "alpha", 11));
        assertEquals(ErrorCode.BATCH_LIMIT_EXCEEDED, ex.code());
        log.info("input list limit=11 max=10; decision code={}", ex.code());

        assertEquals(1, h.envs.list("alice", "alpha", 10).size());
    }
}
