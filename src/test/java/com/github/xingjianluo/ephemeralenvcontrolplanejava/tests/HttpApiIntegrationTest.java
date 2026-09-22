package com.github.xingjianluo.ephemeralenvcontrolplanejava.tests;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * End-to-end HTTP checks against the real Spring context with the simulated
 * backend. Unique env names keep this independent of other @SpringBootTest
 * classes; no external services or real credentials are used.
 */
@SpringBootTest(properties = {
        "cp.reaper-interval-millis=31536000000",   // deterministic: no background sweep
        "cp.scan-page-size=50"
})
@AutoConfigureMockMvc
class HttpApiIntegrationTest {

    @Autowired
    private MockMvc mvc;

    private MvcResult apply(String principal, String tenant, String body) throws Exception {
        return mvc.perform(post("/api/tenants/" + tenant + "/envs")
                        .header("X-Principal", principal)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn();
    }

    @Test
    void applyGetAuditRenewDeleteHappyPath() throws Exception {
        String body = """
                {"name":"http-happy","templateId":"demo","version":"1","ttlSeconds":3600}
                """;
        MvcResult created = apply("alice", "alpha", body);
        String envId = com.jayway.jsonpath.JsonPath
                .read(created.getResponse().getContentAsString(), "$.id");

        mvc.perform(get("/api/tenants/alpha/envs/" + envId).header("X-Principal", "alice"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("READY"))
                .andExpect(jsonPath("$.secrets").isEmpty());

        mvc.perform(post("/api/tenants/alpha/envs/" + envId + "/renew")
                        .header("X-Principal", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ttlSeconds\":7200}"))
                .andExpect(status().isOk());

        mvc.perform(get("/api/tenants/alpha/envs/" + envId + "/audit")
                        .header("X-Principal", "alice"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.action=='RENEW')]").exists());

        // Duplicate apply is idempotent over HTTP: 200, same id, not 201.
        mvc.perform(post("/api/tenants/alpha/envs")
                        .header("X-Principal", "alice")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(envId));

        mvc.perform(delete("/api/tenants/alpha/envs/" + envId)
                        .header("X-Principal", "alice"))
                .andExpect(status().isNoContent());
        // Idempotent delete again.
        mvc.perform(delete("/api/tenants/alpha/envs/" + envId)
                        .header("X-Principal", "alice"))
                .andExpect(status().isNoContent());
    }

    @Test
    void rejectedRequestsCarryDistinguishableCodes() throws Exception {
        mvc.perform(post("/api/tenants/alpha/envs")
                        .header("X-Principal", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"BAD NAME\",\"templateId\":\"demo\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        mvc.perform(post("/api/tenants/alpha/envs")
                        .header("X-Principal", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"http-v9\",\"templateId\":\"demo\",\"version\":\"9\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("TEMPLATE_NOT_FOUND"));

        mvc.perform(post("/api/tenants/alpha/envs"))
                .andExpect(status().is4xxClientError()); // missing principal
    }

    @Test
    void secretsAndCrossTenantAndAdminAreGuarded() throws Exception {
        MvcResult created = apply("alice", "alpha",
                "{\"name\":\"http-guarded\",\"templateId\":\"demo\"}");
        String envId = com.jayway.jsonpath.JsonPath
                .read(created.getResponse().getContentAsString(), "$.id");

        // Viewer cannot read secrets.
        mvc.perform(get("/api/tenants/alpha/envs/" + envId + "?secrets=true")
                        .header("X-Principal", "carol"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        // Other tenant cannot renew.
        mvc.perform(post("/api/tenants/beta/envs/" + envId + "/renew")
                        .header("X-Principal", "dave")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotFound()); // no existence leak

        // Admin endpoints require OWNER role (dave is owner of beta only -> 403
        // check itself passes, but sweep is tenant-neutral; MEMBER bob is 403).
        mvc.perform(post("/api/admin/sweep").header("X-Principal", "bob"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/sweep").header("X-Principal", "alice"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recycled").isNumber());
        mvc.perform(post("/api/admin/reconcile").header("X-Principal", "alice"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orphansRemoved").isNumber());
    }
}
