package com.github.xingjianluo.ephemeralenvcontrolplanejava.tests;

import com.github.xingjianluo.ephemeralenvcontrolplanejava.error.ForbiddenException;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.error.NotFoundException;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.error.UnauthorizedException;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.model.Role;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.web.dto.EnvironmentResponse;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 基于所有者与角色的访问控制：
 * 跨租户隔离、只读成员不可续期/回收/看密钥、越权不得泄露敏感值。
 */
class AccessControlTest extends AbstractControlPlaneTest {

    @Test
    void viewerCanReadButCannotRenewReleaseOrReadSecret() {
        var bob = subject("bob", "team-a", Role.OWNER);
        envService.apply(bob, request("team-a", "sec", "bob", "v1", 60L,
                smallSpecs(), Map.of("dbPassword", "topsecret")));
        var carol = subject("carol", "team-a", Role.VIEWER);

        EnvironmentResponse view = EnvironmentResponse.of(envService.get(carol, "team-a", "sec"));
        assertEquals("READY", view.getStatus(), "VIEWER 可查看非敏感信息");
        // 敏感字段在固化时已掩码为 ***，普通视图里绝不出现真值。
        assertEquals("***", view.getConfig().get("dbPassword"),
                "普通视图中敏感字段必须掩码");

        testLog.info("TEST rbac: carol(VIEWER) renew/release/secret -> expect 403");
        assertThrows(ForbiddenException.class,
                () -> envService.renew(carol, "team-a", "sec", 60L));
        assertThrows(ForbiddenException.class,
                () -> envService.release(carol, "team-a", "sec", "x"));
        assertThrows(ForbiddenException.class,
                () -> envService.getSecret(carol, "team-a", "sec"));
    }

    @Test
    void ownerCanReadSecretAndRenew() {
        var bob = subject("bob", "team-a", Role.OWNER);
        envService.apply(bob, request("team-a", "own", "bob", "v1", 60L,
                smallSpecs(), Map.of("dbPassword", "pw-bob")));
        String secret = envService.getSecret(bob, "team-a", "own");
        assertEquals("pw-bob", secret, "所有者可查看敏感配置");
        assertDoesNotThrow(() -> envService.renew(bob, "team-a", "own", 100L));
        testLog.info("判定依据: owner 读取密钥成功且可续期");
    }

    @Test
    void crossTenantAccessIsForbiddenAndDoesNotLeakExistence() {
        var bob = subject("bob", "team-a", Role.OWNER);
        envService.apply(bob, request("team-a", "hidden", "bob", "v1", 60L,
                smallSpecs(), null));
        var dave = subject("dave", "team-b", Role.TENANT_ADMIN);

        testLog.info("TEST cross tenant: dave(team-b) accessing team-a/hidden -> expect 403");
        assertThrows(ForbiddenException.class,
                () -> envService.get(dave, "team-a", "hidden"));
        assertThrows(ForbiddenException.class,
                () -> envService.release(dave, "team-a", "hidden", "hack"));
        // 列表只列本租户，team-b 视角看不到 team-a 的环境。
        assertTrue(envService.list(dave, 50).stream().noneMatch(e -> "hidden".equals(e.getName())));
    }

    @Test
    void listIsScopedToCallerTenant() {
        var bob = subject("bob", "team-a", Role.OWNER);
        var dave = subject("dave", "team-b", Role.TENANT_ADMIN);
        envService.apply(bob, request("team-a", "a-env", "bob", "v1", 60L, smallSpecs(), null));
        envService.apply(dave, request("team-b", "b-env", "dave", "v1", 60L, smallSpecs(), null));
        assertEquals(1, envService.list(bob, 50).size());
        assertEquals(1, envService.list(dave, 50).size());
    }

    @Test
    void unregisteredOrSpoofedSubjectIsRejectedAtResolution() {
        testLog.info("TEST auth: unknown subject and role spoofing -> expect 401");
        assertThrows(UnauthorizedException.class,
                () -> acs.resolve("mallory", "team-a", "TENANT_ADMIN"));
        // carol 注册为 VIEWER，声明为 ADMIN 视为伪造。
        assertThrows(UnauthorizedException.class,
                () -> acs.resolve("carol", "team-a", "TENANT_ADMIN"));
    }

    @Test
    void nonExistentEnvReturnsNotFoundNotForbiddenForTenantMember() {
        var bob = subject("bob", "team-a", Role.OWNER);
        assertThrows(NotFoundException.class, () -> envService.get(bob, "team-a", "ghost"));
    }
}
