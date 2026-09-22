package com.github.xingjianluo.ephemeralenvcontrolplanejava.tests;

import com.github.xingjianluo.ephemeralenvcontrolplanejava.error.BatchLimitExceededException;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.error.ForbiddenException;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.error.ValidationException;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.model.ActionType;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.model.AuditRecord;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.model.Environment;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.model.Role;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 审计完整性（主体/原因/前后状态、不含敏感值）、非法参数拒绝、批量上限保护。
 */
class AuditAndValidationTest extends AbstractControlPlaneTest {

    @Test
    void everyStateChangeIsAuditedWithActorReasonAndBeforeAfter() {
        var bob = subject("bob", "team-a", Role.OWNER);
        Environment env = envService.apply(bob, request("team-a", "aud", "bob",
                "v1", 60L, smallSpecs(), java.util.Map.of("dbPassword", "super-secret")));
        envService.renew(bob, "team-a", "aud", 90L);
        envService.release(bob, "team-a", "aud", "finished");

        List<AuditRecord> records = auditRepo.findByEnv("team-a", env.getId(), 100);
        testLog.info("TEST audit: recorded actions count={} for env {}", records.size(), env.getName());
        assertFalse(records.isEmpty());
        // 倒序：RELEASED 最后一条在前。
        assertEquals(ActionType.RELEASE, records.get(0).action());
        assertTrue(records.stream().anyMatch(r -> r.action() == ActionType.APPLY));
        assertTrue(records.stream().anyMatch(r -> r.action() == ActionType.MARK_READY));
        assertTrue(records.stream().anyMatch(r -> r.action() == ActionType.RENEW));
        for (AuditRecord r : records) {
            assertEquals("bob", r.actor(), "审计必须记录触发主体");
            assertNotNull(r.reason(), "审计必须记录原因");
            if (r.action() != ActionType.APPLY) {
                assertNotNull(r.before(), "非创建动作必须记录前状态");
            }
            assertNotNull(r.after(), "所有审计记录必须有后状态");
            assertFalse(r.reason().contains("super-secret"),
                    "审计原因中绝不允许出现敏感值");
            assertFalse(r.toString().contains("super-secret"),
                    "审计记录整体不得包含敏感值");
        }
    }

    @Test
    void viewerCanQueryAuditForEnvButNotCrossTenant() {
        var bob = subject("bob", "team-a", Role.OWNER);
        Environment env = envService.apply(bob, request("team-a", "aud2", "bob",
                "v1", 60L, smallSpecs(), null));
        var carol = subject("carol", "team-a", Role.VIEWER);
        List<AuditRecord> seen = auditService.queryForEnv(carol, env, 10);
        assertFalse(seen.isEmpty(), "VIEWER 可审计查询本租户环境");

        var dave = subject("dave", "team-b", Role.TENANT_ADMIN);
        assertThrows(ForbiddenException.class, () -> auditService.queryForEnv(dave, env, 10),
                "跨租户审计查询必须拒绝");
    }

    @Test
    void illegalParametersAreRejectedWithDistinctValidationErrors() {
        var bob = subject("bob", "team-a", Role.OWNER);
        testLog.info("TEST validation: blank name / bad charset / bad ttl / empty resources");
        assertThrows(ValidationException.class, () -> envService.apply(bob,
                request("team-a", " ", "bob", "v1", 60L, smallSpecs(), null)));
        assertThrows(ValidationException.class, () -> envService.apply(bob,
                request("team-a", "bad/name", "bob", "v1", 60L, smallSpecs(), null)));
        assertThrows(ValidationException.class, () -> envService.apply(bob,
                request("team-a", "ok-name", "bob", "v1", 0L, smallSpecs(), null)));
        assertThrows(ValidationException.class, () -> envService.apply(bob,
                request("team-a", "ok-name2", "bob", "v1", 60L, List.of(), null)));
        assertThrows(ValidationException.class, () -> envService.apply(bob,
                request("team-a", "ok-name3", "bob", "v1", 60L,
                        List.of(new com.github.xingjianluo.ephemeralenvcontrolplanejava.model.ResourceSpec(
                                "k", "img", -1, 100)), null)));
        assertEquals(0, backend.size(), "非法参数请求不得分配资源");
        assertEquals(0, quotaService.getQuota("team-a").getUsedEnvs(), "非法参数不得占用配额");
    }

    @Test
    void listAndAuditRejectLimitBeyondConfiguredMax() {
        var bob = subject("bob", "team-a", Role.OWNER);
        testLog.info("TEST batch limit: listMaxLimit={}, requesting oversize", props.getListMaxLimit());
        assertThrows(BatchLimitExceededException.class, () -> envService.list(bob, 500));
        assertThrows(BatchLimitExceededException.class,
                () -> auditService.query(bob, "team-a", null, 10_000));
    }

    @Test
    void ttlBeyondMaximumIsRejected() {
        var bob = subject("bob", "team-a", Role.OWNER);
        long tooLarge = props.getLease().getMaxTtlSeconds() + 1;
        ValidationException ex = assertThrows(ValidationException.class, () -> envService.apply(bob,
                request("team-a", "ttl-big", "bob", "v1", tooLarge, smallSpecs(), null)));
        assertTrue(ex.getMessage().contains("ttlSeconds"));
    }
}
