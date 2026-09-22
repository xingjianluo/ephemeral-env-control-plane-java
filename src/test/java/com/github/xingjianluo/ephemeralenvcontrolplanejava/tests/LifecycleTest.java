package com.github.xingjianluo.ephemeralenvcontrolplanejava.tests;

import com.github.xingjianluo.ephemeralenvcontrolplanejava.error.BackendFailureException;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.error.InvalidStateException;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.error.NameConflictException;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.model.Environment;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.model.EnvironmentStatus;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.model.Role;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.web.dto.ApplyRequest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 申请/就绪/续期/回收：状态机、幂等、失败不就绪。 */
class LifecycleTest extends AbstractControlPlaneTest {

    @Test
    void applyCreatesReadyEnvironment() {
        var bob = subject("bob", "team-a", Role.OWNER);
        ApplyRequest req = request("team-a", "env-1", "bob", "v1", 60L,
                smallSpecs(), Map.of("replicas", 2));
        testLog.info("TEST applyCreatesReady: input tenant=team-a name=env-1 replicas=2 -> expect READY");
        Environment env = envService.apply(bob, req);

        assertEquals(EnvironmentStatus.READY, env.getStatus());
        assertEquals(clock.instant().toEpochMilli() + 60_000L, env.getExpiresAtMillis());
        assertEquals("env-1", env.getName());
        assertEquals(1, backend.size(), "READY 环境应恰好占用 1 个后端资源");
        testLog.info("判定依据: status={}, backendSize=1, expiresAt={}", env.getStatus(),
                env.getExpiresAtMillis());
    }

    @Test
    void duplicateApplyWithSameParametersReturnsSameEnvironment() {
        var bob = subject("bob", "team-a", Role.OWNER);
        ApplyRequest req = request("team-a", "dup", "bob", "v1", 60L, smallSpecs(), Map.of());
        Environment first = envService.apply(bob, req);
        Environment second = envService.apply(bob, req);
        testLog.info("TEST duplicate apply: same id expected: {} == {}", first.getId(), second.getId());
        assertSame(first, second, "同名同参数重复申请必须返回同一环境对象");
        assertEquals(1, backend.size(), "重复申请不得重复创建后端资源");
    }

    @Test
    void sameNameDifferentParametersIsNameConflict() {
        var bob = subject("bob", "team-a", Role.OWNER);
        envService.apply(bob, request("team-a", "dup2", "bob", "v1", 60L, smallSpecs(), Map.of()));
        ApplyRequest changed = request("team-a", "dup2", "bob", "v1", 120L, smallSpecs(), Map.of());
        testLog.info("TEST name conflict: same name dup2 but ttl 60 -> 120, expect NAME_CONFLICT");
        assertThrows(NameConflictException.class, () -> envService.apply(bob, changed));
    }

    @Test
    void failedProvisioningIsNotReadyReleasesQuotaAndBackendHasNoResource() {
        var bob = subject("bob", "team-a", Role.OWNER);
        // 第二个资源分配失败，制造部分失败。
        backend.failAllocateRule(ctx -> ctx.attempt() == 2);
        var specs = List.of(
                new com.github.xingjianluo.ephemeralenvcontrolplanejava.model.ResourceSpec(
                        "service", "a", 1, 256),
                new com.github.xingjianluo.ephemeralenvcontrolplanejava.model.ResourceSpec(
                        "worker", "b", 1, 256));
        testLog.info("TEST partial failure: 2 resources, 2nd allocation fails");
        assertThrows(BackendFailureException.class, () -> envService.apply(
                bob, request("team-a", "fail-env", "bob", "v1", 60L, specs, Map.of())));

        Environment failed = envService.get(bob, "team-a", "fail-env");
        assertEquals(EnvironmentStatus.FAILED, failed.getStatus(), "失败环境不得标记为 READY");
        assertNotNull(failed.getFailureReason(), "必须留下可查询的失败原因");
        assertEquals(0, backend.size(), "第一个资源必须被回滚，无孤立资源");
        var quota = quotaService.getQuota("team-a");
        assertEquals(0, quota.getUsedEnvs(), "失败后环境槽位必须归还");
        assertEquals(0, quota.getUsedCpu(), "失败后 CPU 配额必须归还");
        testLog.info("判定依据: status=FAILED, backendSize=0, usedEnvs=0, usedCpu=0");
    }

    @Test
    void failedEnvironmentCanNeverTransitionToReady() {
        var bob = subject("bob", "team-a", Role.OWNER);
        backend.failAllocateRule(ctx -> true);
        assertThrows(BackendFailureException.class, () -> envService.apply(
                bob, request("team-a", "fail-2", "bob", "v1", 60L, smallSpecs(), Map.of())));
        Environment failed = envService.get(bob, "team-a", "fail-2");
        assertThrows(InvalidStateException.class, () -> failed.renew(0L, 1000L),
                "FAILED -> READY/续期 路径必须被状态机拒绝");
    }

    @Test
    void releaseIsIdempotentAndReturnsResourcesAndQuota() {
        var bob = subject("bob", "team-a", Role.OWNER);
        Environment env = envService.apply(bob,
                request("team-a", "rel", "bob", "v1", 60L, smallSpecs(), Map.of()));
        Environment released1 = envService.release(bob, "team-a", "rel", "done");
        Environment released2 = envService.release(bob, "team-a", "rel", "done again");
        assertEquals(EnvironmentStatus.RELEASED, released1.getStatus());
        assertSame(released1, released2, "重复回收幂等返回同一终态");
        assertEquals(0, backend.size(), "资源只能释放一次");
        assertEquals(0, quotaService.getQuota("team-a").getUsedEnvs(), "配额只能归还一次");
        testLog.info("判定依据: 两次 release 后 backendSize=0, usedEnvs=0, 无异常");
    }

    @Test
    void renewExtendsExpiryForReadyOnly() {
        var bob = subject("bob", "team-a", Role.OWNER);
        Environment env = envService.apply(bob,
                request("team-a", "ren", "bob", "v1", 60L, smallSpecs(), Map.of()));
        clock.advance(50_000);
        long oldExpiry = env.getExpiresAtMillis();
        Environment renewed = envService.renew(bob, "team-a", "ren", 60L);
        assertEquals(clock.instant().toEpochMilli() + 60_000L, renewed.getExpiresAtMillis());
        assertTrue(renewed.getExpiresAtMillis() > oldExpiry);
        testLog.info("判定依据: 续期后到期时间 {} > 旧到期 {}", renewed.getExpiresAtMillis(), oldExpiry);
    }

    @Test
    void tenantNameIsolationAllowsSameNameAcrossTenants() {
        var bob = subject("bob", "team-a", Role.OWNER);
        var dave = subject("dave", "team-b", Role.TENANT_ADMIN);
        Environment a = envService.apply(bob,
                request("team-a", "shared-name", "bob", "v1", 60L, smallSpecs(), Map.of()));
        Environment b = envService.apply(dave,
                request("team-b", "shared-name", "dave", "v1", 60L, smallSpecs(), Map.of()));
        assertNotEquals(a.getId(), b.getId(), "不同租户同名环境必须相互隔离");
        assertEquals(2, backend.size());
    }
}
