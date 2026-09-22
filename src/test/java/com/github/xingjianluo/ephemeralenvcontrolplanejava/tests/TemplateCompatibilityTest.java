package com.github.xingjianluo.ephemeralenvcontrolplanejava.tests;

import com.github.xingjianluo.ephemeralenvcontrolplanejava.error.TemplateIncompatibleException;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.error.ValidationException;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.model.Environment;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.model.EnvironmentStatus;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.model.Role;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 模板版本演进：
 * 未知版本/未知字段必须明确拒绝；缺失字段取稳定默认值；
 * 新版本只能加字段；升级不追溯破坏已就绪环境。
 */
class TemplateCompatibilityTest extends AbstractControlPlaneTest {

    @Test
    void unsupportedVersionIsRejected() {
        var bob = subject("bob", "team-a", Role.OWNER);
        testLog.info("TEST template: unknown version v99 -> expect TEMPLATE_INCOMPATIBLE");
        assertThrows(TemplateIncompatibleException.class, () -> envService.apply(
                bob, request("team-a", "t1", "bob", "v99", 60L, smallSpecs(), null)));
    }

    @Test
    void unknownFieldIsRejectedNotSilentlyIgnored() {
        var bob = subject("bob", "team-a", Role.OWNER);
        testLog.info("TEST template: v1 不认识 newField -> expect rejection (no silent ignore)");
        assertThrows(TemplateIncompatibleException.class, () -> envService.apply(
                bob, request("team-a", "t2", "bob", "v1", 60L, smallSpecs(),
                        Map.of("newField", "x"))));
    }

    @Test
    void missingFieldsGetStableDefaultsAndNewVersionAddsField() {
        var bob = subject("bob", "team-a", Role.OWNER);
        // v1 不传任何 overrides：使用默认值。
        Environment v1Env = envService.apply(bob,
                request("team-a", "t3", "bob", "v1", 60L, smallSpecs(), null));
        assertEquals("app:1.0", v1Env.getConfigValues().get("image"));
        assertEquals(1, v1Env.getConfigValues().get("replicas"));
        assertFalse(v1Env.getConfigValues().containsKey("featureFlag"),
                "v1 环境不应出现 v2 才有的字段");

        // v2 新增字段带默认值，旧字段默认值不变。
        Environment v2Env = envService.apply(bob,
                request("team-a", "t4", "bob", "v2", 60L, smallSpecs(), null));
        assertEquals(false, v2Env.getConfigValues().get("featureFlag"));
        assertEquals("app:1.0", v2Env.getConfigValues().get("image"));
        testLog.info("判定依据: v1 默认值稳定, v2 新增 featureFlag=false");
    }

    @Test
    void registeringVersionThatRemovesFieldOrChangesDefaultIsRejected() {
        testLog.info("TEST template evolution: 删除字段 / 修改默认值 / 改敏感字段 -> expect rejection");
        // 删除 replicas 字段。
        assertThrows(TemplateIncompatibleException.class, () -> templates.register("v3",
                "dbPassword", Map.of("image", "app:1.0", "dbPassword", "x")));
        // 修改既有字段默认值。
        assertThrows(TemplateIncompatibleException.class, () -> templates.register("v3",
                "dbPassword", Map.of("image", "app:2.0", "replicas", 1,
                        "dbPassword", "x", "featureFlag", false)));
        // 改变敏感字段名。
        assertThrows(TemplateIncompatibleException.class, () -> templates.register("v3",
                "token", Map.of("image", "app:1.0", "replicas", 1,
                        "dbPassword", "secret-v1", "featureFlag", false, "token", "t")));
    }

    @Test
    void duplicateVersionRegistrationIsRejected() {
        assertThrows(ValidationException.class, () -> templates.register("v1", "dbPassword",
                Map.of("image", "app:1.0", "replicas", 1, "dbPassword", "secret-v1")));
    }

    @Test
    void templateUpgradeDoesNotRetroactivelyChangeReadyEnvironments() {
        var bob = subject("bob", "team-a", Role.OWNER);
        Environment env = envService.apply(bob,
                request("team-a", "frozen", "bob", "v1", 60L, smallSpecs(), null));
        assertEquals(EnvironmentStatus.READY, env.getStatus());
        // 再注册一个兼容的 v3（仅新增字段，默认值不变），不影响已就绪的 v1 环境。
        templates.register("v3", "dbPassword",
                Map.of("image", "app:1.0", "replicas", 1, "dbPassword", "secret-v1",
                        "featureFlag", false, "extra", "n/a"));
        Environment again = envService.get(bob, "team-a", "frozen");
        assertEquals("v1", again.getTemplateVersion(), "已就绪环境的模板版本不被追溯改写");
        assertFalse(again.getConfigValues().containsKey("extra"));
        assertFalse(again.getConfigValues().containsKey("featureFlag"));
        testLog.info("判定依据: 注册 v3 后，READY 的 v1 环境配置快照保持不变");
    }
}
