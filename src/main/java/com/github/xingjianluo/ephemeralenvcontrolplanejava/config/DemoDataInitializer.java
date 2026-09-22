package com.github.xingjianluo.ephemeralenvcontrolplanejava.config;

import com.github.xingjianluo.ephemeralenvcontrolplanejava.service.AccessControlService;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.service.QuotaService;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.service.TemplateService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

/**
 * 本地演示数据（ecp.bootstrap-demo=false 可关闭）：
 * - 模板 v1 基础版本，v2 在 v1 基础上“仅新增字段”（featureFlag 默认 false）；
 * - 两个租户 team-a / team-b 的配额，用于验证租户隔离与配额竞争；
 * - 每租户一个管理员、所有者、只读成员，用于验证 RBAC。
 */
@Configuration
public class DemoDataInitializer {

    private static final Logger log = LoggerFactory.getLogger(DemoDataInitializer.class);

    @Bean
    public ApplicationRunner demoDataRunner(ControlPlaneProperties properties,
                                            TemplateService templateService,
                                            QuotaService quotaService,
                                            AccessControlService accessControlService) {
        return args -> {
            if (!properties.isBootstrapDemo()) {
                return;
            }
            // 模板：v1 -> v2 只新增字段，默认值语义保持。
            templateService.register("v1", "dbPassword",
                    Map.of("image", "app:1.0", "replicas", 1, "dbPassword", "changeme"));
            templateService.register("v2", "dbPassword",
                    Map.of("image", "app:1.0", "replicas", 1, "dbPassword", "changeme",
                            "featureFlag", false));

            // 配额：team-a 2 环境 / 4 cpu / 2048MB；team-b 1 环境。
            quotaService.setLimits("team-a", 2, 4, 2048);
            quotaService.setLimits("team-b", 1, 4, 2048);

            // 主体。
            accessControlService.register("alice", "team-a", "TENANT_ADMIN");
            accessControlService.register("bob", "team-a", "OWNER");
            accessControlService.register("carol", "team-a", "VIEWER");
            accessControlService.register("dave", "team-b", "TENANT_ADMIN");

            log.info("demo data initialized: templates v1/v2, tenants team-a/team-b, subjects alice/bob/carol/dave");
        };
    }
}
