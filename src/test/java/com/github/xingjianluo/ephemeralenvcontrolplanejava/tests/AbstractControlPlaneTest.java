package com.github.xingjianluo.ephemeralenvcontrolplanejava.tests;

import com.github.xingjianluo.ephemeralenvcontrolplanejava.backend.InMemoryResourceBackend;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.config.ControlPlaneProperties;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.model.ResourceSpec;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.model.Role;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.model.Subject;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.repo.InMemoryAuditRepository;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.repo.InMemoryEnvironmentRepository;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.repo.InMemoryQuotaRepository;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.service.AccessControlService;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.service.AuditService;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.service.EnvironmentService;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.service.QuotaService;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.service.Reconciler;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.service.LeaseExpiryScanner;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.service.TemplateService;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.web.dto.ApplyRequest;
import org.junit.jupiter.api.BeforeEach;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

/**
 * 纯单元测试基类：手工装配全部组件（不启动 Spring、不碰真实集群/外部服务/真实凭据）。
 * 使用固定+可推进的时钟，保证过期相关并发测试结果确定。
 */
abstract class AbstractControlPlaneTest {

    protected final Logger testLog = LoggerFactory.getLogger(getClass());

    protected InMemoryResourceBackend backend;
    protected InMemoryEnvironmentRepository envRepo;
    protected InMemoryQuotaRepository quotaRepo;
    protected InMemoryAuditRepository auditRepo;
    protected AccessControlService acs;
    protected QuotaService quotaService;
    protected TemplateService templates;
    protected AuditService auditService;
    protected EnvironmentService envService;
    protected LeaseExpiryScanner scanner;
    protected Reconciler reconciler;
    protected ControlPlaneProperties props;

    /** 固定起点时钟；advance 推进后，EnvironmentService 的 now() 同步前进。 */
    protected MutableClock clock;

    static class MutableClock extends Clock {
        private long millis = 1_000_000_000_000L;

        void advance(long deltaMillis) {
            this.millis += deltaMillis;
        }

        void setMillis(long m) {
            this.millis = m;
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(millis);
        }
    }

    @BeforeEach
    void setUp() {
        testLog.info("---- test input setup ----");
        backend = new InMemoryResourceBackend();
        envRepo = new InMemoryEnvironmentRepository();
        quotaRepo = new InMemoryQuotaRepository();
        auditRepo = new InMemoryAuditRepository();
        props = new ControlPlaneProperties();
        clock = new MutableClock();

        acs = new AccessControlService();
        quotaService = new QuotaService(quotaRepo);
        templates = new TemplateService();
        auditService = new AuditService(auditRepo, acs, props);
        envService = new EnvironmentService(envRepo, quotaService, backend, templates,
                acs, auditService, props);
        envService.setClock(clock);
        scanner = new LeaseExpiryScanner(envRepo, envService, props);
        reconciler = new Reconciler(backend, envRepo, envService);

        templates.register("v1", "dbPassword",
                Map.of("image", "app:1.0", "replicas", 1, "dbPassword", "secret-v1"));
        templates.register("v2", "dbPassword",
                Map.of("image", "app:1.0", "replicas", 1, "dbPassword", "secret-v1",
                        "featureFlag", false));
        quotaService.setLimits("team-a", 2, 4, 2048);
        quotaService.setLimits("team-b", 1, 4, 2048);
        acs.register("alice", "team-a", "TENANT_ADMIN");
        acs.register("bob", "team-a", "OWNER");
        acs.register("carol", "team-a", "VIEWER");
        acs.register("dave", "team-b", "TENANT_ADMIN");
        testLog.info("setup done: templates v1/v2, quotas team-a(2,4,2048) team-b(1,4,2048)");
    }

    protected Subject subject(String name, String tenant, Role role) {
        return new Subject(name, tenant, role);
    }

    protected ApplyRequest request(String tenant, String name, String owner, String version,
                                   Long ttlSeconds, List<ResourceSpec> resources,
                                   Map<String, Object> overrides) {
        ApplyRequest r = new ApplyRequest();
        r.setTenantId(tenant);
        r.setName(name);
        r.setOwner(owner);
        r.setTemplateVersion(version);
        r.setTtlSeconds(ttlSeconds);
        r.setResources(resources);
        r.setConfigOverrides(overrides);
        return r;
    }

    protected List<ResourceSpec> smallSpecs() {
        return List.of(new ResourceSpec("service", "app:1.0", 1, 512));
    }

    protected List<ResourceSpec> bigSpecs() {
        return List.of(new ResourceSpec("service", "app:1.0", 8, 4096));
    }
}
