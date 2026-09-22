package com.github.xingjianluo.ephemeralenvcontrolplanejava.tests;

import com.github.xingjianluo.ephemeralenvcontrolplanejava.model.AuditRecord;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.model.Environment;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AuditTrailTest {

    private static final Logger log = LoggerFactory.getLogger(AuditTrailTest.class);
    private final TestHarness h = new TestHarness();

    @Test
    void everyTransitionIsAuditedWithActorReasonAndStates() {
        Environment env = h.fresh(h.alice(), "audited");
        String id = env.id();
        h.envs.renew("alice", "alpha", id, 1200L);
        h.clock.advanceSeconds(5000);
        h.reaper.sweepOnce(); // expire + release path

        List<AuditRecord> records = h.audits.query("alpha", id, 0, 50);
        log.info("input full lifecycle env={}; decision auditCount={}", id, records.size());
        assertTrue(records.size() >= 5, "apply/ready/renew/expire(or release)/release");

        // Newest first; verify content shape and actor.
        for (AuditRecord a : records) {
            assertNotNull(a.id());
            assertNotNull(a.at());
            assertEquals("alpha", a.tenantId());
            assertNotNull(a.actor());
            assertNotNull(a.reason());
            assertFalse(a.reason().isBlank());
        }
        // Renewal entry names alice and both states.
        AuditRecord renew = records.stream()
                .filter(a -> a.action().name().equals("RENEW")).findFirst().orElseThrow();
        assertEquals("alice", renew.actor());
        assertEquals("READY", renew.toStatus().name());
        log.info("renew audit actor={} {}->{} reason='{}'",
                renew.actor(), renew.fromStatus(), renew.toStatus(), renew.reason());
    }

    @Test
    void auditNeverContainsSecretValues() {
        Environment env = h.envs.apply(new com.github.xingjianluo.ephemeralenvcontrolplanejava
                .service.ApplyCommand(h.alice(), "secret-audit", "demo", "1",
                java.util.Map.of("api_key", "super-secret-token-42"), 3600L))
                .environment();
        List<AuditRecord> records = h.audits.query("alpha", env.id(), 0, 50);
        String blob = records.stream().map(AuditRecord::toString)
                .reduce("", String::concat);
        log.info("input apply with api_key; decision auditEntries={} secretLeaked={}",
                records.size(), blob.contains("super-secret-token-42"));
        assertFalse(blob.contains("super-secret-token-42"),
                "secrets must never appear in audit records");
        assertFalse(blob.contains("api_key"));
    }

    @Test
    void auditIsPagedAndTenantScoped() {
        for (int i = 0; i < 3; i++) {
            h.fresh(h.alice(), "page-" + i);
        }
        assertEquals(0, h.audits.query("beta", null, 0, 50).size(),
                "tenant isolation in audit");
        int alphaCount = h.audits.count("alpha", null);
        List<?> page = h.audits.query("alpha", null, 0, 2);
        assertEquals(2, page.size());
        log.info("input audit paging; decision totalAlpha={} pageSize=2", alphaCount);
        assertTrue(alphaCount >= 6);
    }
}
