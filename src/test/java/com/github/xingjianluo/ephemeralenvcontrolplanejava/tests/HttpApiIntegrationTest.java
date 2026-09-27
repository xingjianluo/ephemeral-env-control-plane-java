package com.github.xingjianluo.ephemeralenvcontrolplanejava.tests;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 端到端 HTTP 验证（Spring MockMvc，不监听真实端口、不依赖外部服务/真实凭据）。
 * 演示数据由 DemoDataInitializer 提供：alice(team-a ADMIN)/bob(team-a OWNER)/
 * carol(team-a VIEWER)/dave(team-b ADMIN)，模板 v1/v2，team-a 配额 2。
 */
@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
class HttpApiIntegrationTest {

    @Autowired
    private MockMvc mvc;
    private final ObjectMapper json = new ObjectMapper();

    private String applyBody(String tenant, String name, String owner, String version,
                             Long ttl, int cpu, int mem, Map<String, Object> overrides) {
        try {
            Map<String, Object> body = Map.of(
                    "tenantId", tenant,
                    "name", name,
                    "owner", owner,
                    "templateVersion", version,
                    "ttlSeconds", ttl,
                    "resources", List.of(Map.of("kind", "service", "image", "app:1.0",
                            "cpu", cpu, "memoryMb", mem)),
                    "configOverrides", overrides);
            return json.writeValueAsString(body);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void fullLifecycleOverHttpWithRbacAndErrorCodes() throws Exception {
        String body = applyBody("team-a", "web-env", "bob", "v2", 120L, 1, 256,
                Map.of("dbPassword", "db-pw", "featureFlag", true));

        // 1) bob 申请成功；响应不含真实密钥。
        mvc.perform(post("/api/environments")
                        .header("X-Subject", "bob").header("X-Tenant", "team-a").header("X-Role", "OWNER")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("READY"))
                .andExpect(jsonPath("$.config.featureFlag").value(true))
                .andExpect(jsonPath("$.config.dbPassword").value("***"));

        // 2) 重复申请（同参数）幂等。
        mvc.perform(post("/api/environments")
                        .header("X-Subject", "bob").header("X-Tenant", "team-a").header("X-Role", "OWNER")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("READY"));

        // 3) carol(VIEWER) 尝试续期 -> 403，错误码 FORBIDDEN 可区分。
        mvc.perform(post("/api/environments/web-env/renew")
                        .header("X-Subject", "carol").header("X-Tenant", "team-a").header("X-Role", "VIEWER")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("tenantId", "team-a", "ttlSeconds", 60))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("FORBIDDEN"));

        // 4) carol 读取密钥 -> 403，且响应不含 db-pw。
        mvc.perform(get("/api/environments/web-env/secret?tenantId=team-a")
                        .header("X-Subject", "carol").header("X-Tenant", "team-a").header("X-Role", "VIEWER"))
                .andExpect(status().isForbidden())
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("db-pw"))));

        // 5) bob 读取密钥 -> 200，仅授权主体可见。
        mvc.perform(get("/api/environments/web-env/secret?tenantId=team-a")
                        .header("X-Subject", "bob").header("X-Tenant", "team-a").header("X-Role", "OWNER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.secret").value("db-pw"));

        // 6) dave(team-b) 访问 team-a 资源 -> 403。
        mvc.perform(get("/api/environments/web-env?tenantId=team-a")
                        .header("X-Subject", "dave").header("X-Tenant", "team-b").header("X-Role", "TENANT_ADMIN"))
                .andExpect(status().isForbidden());

        // 7) 缺少主体头 -> 401。
        mvc.perform(get("/api/environments/web-env?tenantId=team-a"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UNAUTHORIZED"));

        // 8) bob 回收 -> 200 RELEASED；再次回收幂等。
        mvc.perform(delete("/api/environments/web-env?tenantId=team-a&reason=done")
                        .header("X-Subject", "bob").header("X-Tenant", "team-a").header("X-Role", "OWNER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RELEASED"));
        mvc.perform(delete("/api/environments/web-env?tenantId=team-a")
                        .header("X-Subject", "bob").header("X-Tenant", "team-a").header("X-Role", "OWNER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RELEASED"));

        // 9) 已回收环境的普通视图不含内部细节。
        mvc.perform(get("/api/environments/web-env?tenantId=team-a")
                        .header("X-Subject", "bob").header("X-Tenant", "team-a").header("X-Role", "OWNER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.owner").doesNotExist())
                .andExpect(jsonPath("$.config").doesNotExist());
    }

    @Test
    void suspendResumeOverHttp() throws Exception {
        String body = applyBody("team-a", "web-suspend", "bob", "v2", 300L, 1, 256,
                Map.of("dbPassword", "db-pw"));
        mvc.perform(post("/api/environments")
                        .header("X-Subject", "bob").header("X-Tenant", "team-a").header("X-Role", "OWNER")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("READY"));

        // 1) carol(VIEWER) 挂起 -> 403。
        mvc.perform(post("/api/environments/web-suspend/suspend?tenantId=team-a")
                        .header("X-Subject", "carol").header("X-Tenant", "team-a").header("X-Role", "VIEWER"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("FORBIDDEN"));

        // 2) bob 挂起 -> 200 SUSPENDED；身份与配置快照仍可见，敏感字段掩码。
        mvc.perform(post("/api/environments/web-suspend/suspend?tenantId=team-a&reason=idle")
                        .header("X-Subject", "bob").header("X-Tenant", "team-a").header("X-Role", "OWNER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUSPENDED"))
                .andExpect(jsonPath("$.owner").value("bob"))
                .andExpect(jsonPath("$.suspendedAtMillis").exists())
                .andExpect(jsonPath("$.config.dbPassword").value("***"));

        // 3) 重复挂起幂等；配额已让出（usedEnvs=0）。
        mvc.perform(post("/api/environments/web-suspend/suspend?tenantId=team-a")
                        .header("X-Subject", "bob").header("X-Tenant", "team-a").header("X-Role", "OWNER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUSPENDED"));
        mvc.perform(get("/api/admin/quotas?tenantId=team-a")
                        .header("X-Subject", "alice").header("X-Tenant", "team-a").header("X-Role", "TENANT_ADMIN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.usedEnvs").value(0))
                .andExpect(jsonPath("$.usedCpu").value(0));

        // 4) 挂起中读取密钥 -> 409（仅 READY 可取密钥），响应不含真实密钥。
        mvc.perform(get("/api/environments/web-suspend/secret?tenantId=team-a")
                        .header("X-Subject", "bob").header("X-Tenant", "team-a").header("X-Role", "OWNER"))
                .andExpect(status().isConflict())
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("db-pw"))));

        // 5) carol 恢复 -> 403；bob 恢复 -> 200 READY，配置快照保持挂起前。
        mvc.perform(post("/api/environments/web-suspend/resume?tenantId=team-a")
                        .header("X-Subject", "carol").header("X-Tenant", "team-a").header("X-Role", "VIEWER"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/environments/web-suspend/resume?tenantId=team-a")
                        .header("X-Subject", "bob").header("X-Tenant", "team-a").header("X-Role", "OWNER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("READY"))
                .andExpect(jsonPath("$.templateVersion").value("v2"))
                .andExpect(jsonPath("$.config.dbPassword").value("***"));

        // 6) 重复恢复幂等；配额重新占用恰好一次。
        mvc.perform(post("/api/environments/web-suspend/resume?tenantId=team-a")
                        .header("X-Subject", "bob").header("X-Tenant", "team-a").header("X-Role", "OWNER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("READY"));
        mvc.perform(get("/api/admin/quotas?tenantId=team-a")
                        .header("X-Subject", "alice").header("X-Tenant", "team-a").header("X-Role", "TENANT_ADMIN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.usedEnvs").value(1))
                .andExpect(jsonPath("$.usedCpu").value(1));

        // 7) 恢复后密钥可再次读取。
        mvc.perform(get("/api/environments/web-suspend/secret?tenantId=team-a")
                        .header("X-Subject", "bob").header("X-Tenant", "team-a").header("X-Role", "OWNER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.secret").value("db-pw"));
    }

    @Test
    void quotaAndTemplateErrorsAreDistinguishableOverHttp() throws Exception {
        // 抬高环境数上限，使本轮专门压测 CPU 维度（maxCpu=4）的拒绝原因。
        mvc.perform(post("/api/admin/quotas?tenantId=team-a&maxEnvs=10&maxCpu=4&maxMemoryMb=4096")
                        .header("X-Subject", "alice").header("X-Tenant", "team-a").header("X-Role", "TENANT_ADMIN"))
                .andExpect(status().isOk());
        String e1 = applyBody("team-a", "q1", "bob", "v1", 60L, 1, 256, Map.of());
        String e2 = applyBody("team-a", "q2", "bob", "v1", 120L, 2, 1024, Map.of());
        mvc.perform(post("/api/environments")
                        .header("X-Subject", "bob").header("X-Tenant", "team-a").header("X-Role", "OWNER")
                        .contentType(MediaType.APPLICATION_JSON).content(e1))
                .andExpect(status().isOk());
        mvc.perform(post("/api/environments")
                        .header("X-Subject", "bob").header("X-Tenant", "team-a").header("X-Role", "OWNER")
                        .contentType(MediaType.APPLICATION_JSON).content(e2))
                .andExpect(status().isOk());
        // CPU 已用 3/4：再要 2 cpu -> 409 且消息指明 cpu。
        String e3 = applyBody("team-a", "q3", "bob", "v1", 60L, 2, 256, Map.of());
        mvc.perform(post("/api/environments")
                        .header("X-Subject", "bob").header("X-Tenant", "team-a").header("X-Role", "OWNER")
                        .contentType(MediaType.APPLICATION_JSON).content(e3))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("QUOTA_EXCEEDED"))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("cpu quota exceeded")));

        // 未知模板字段 -> 400 TEMPLATE_INCOMPATIBLE。
        String badField = applyBody("team-a", "q4", "bob", "v1", 60L, 1, 256,
                Map.of("bogus", "x"));
        mvc.perform(post("/api/environments")
                        .header("X-Subject", "alice").header("X-Tenant", "team-a").header("X-Role", "TENANT_ADMIN")
                        .contentType(MediaType.APPLICATION_JSON).content(badField))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("TEMPLATE_INCOMPATIBLE"));
    }
}
