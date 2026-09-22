package com.github.xingjianluo.ephemeralenvcontrolplanejava.tests;

import com.github.xingjianluo.ephemeralenvcontrolplanejava.backend.ResourceBackend;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.backend.SimulatedResourceBackend;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.config.ControlPlaneProperties;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.model.Environment;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.model.Role;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.model.Subject;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.model.TenantQuota;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.repo.AuditRepository;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.repo.EnvironmentRepository;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.repo.InMemoryAuditRepository;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.repo.InMemoryEnvironmentRepository;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.service.*;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.template.TemplateDescriptor;

import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * Plain-JUnit wiring of the real service graph (no Spring context needed),
 * with the simulated backend and a controllable clock. No real clusters,
 * external services or credentials are involved.
 */
public class TestHarness {

    public final MutableTimeSource clock;
    public final EnvironmentRepository envRepo;
    public final AuditRepository auditRepo;
    public final SimulatedResourceBackend backend;
    public final QuotaService quotas;
    public final TemplateService templates;
    public final AuditService audits;
    public final AccessService access;
    public final DefaultEnvironmentService envs;
    public final ReaperService reaper;
    public final ReconcilerService reconciler;
    public final InMemorySubjectDirectory directory;
    public final ControlPlaneProperties props;

    public TestHarness() {
        this(Instant.parse("2026-09-22T00:00:00Z"));
    }

    public TestHarness(Instant start) {
        clock = new MutableTimeSource(start);
        envRepo = new InMemoryEnvironmentRepository();
        auditRepo = new InMemoryAuditRepository();
        backend = new SimulatedResourceBackend(clock);
        directory = new InMemorySubjectDirectory();
        props = new ControlPlaneProperties();

        quotas = new DefaultQuotaService();
        templates = new DefaultTemplateService();
        audits = new DefaultAuditService(auditRepo, clock);
        access = new DefaultAccessService(directory);
        envs = new DefaultEnvironmentService(envRepo, templates, quotas, audits,
                access, backend, clock, props);
        reaper = new DefaultReaperService(envRepo, envs, clock, props);
        reconciler = new DefaultReconcilerService(envRepo, backend, quotas,
                audits, clock, props);
        seed();
    }

    private void seed() {
        quotas.setQuota(new TenantQuota("alpha", 3, 6));
        quotas.setQuota(new TenantQuota("beta", 2, 4));
        directory.register(new Subject("alice", "alpha", Set.of(Role.OWNER)));
        directory.register(new Subject("bob", "alpha", Set.of(Role.MEMBER)));
        directory.register(new Subject("carol", "alpha", Set.of(Role.VIEWER)));
        directory.register(new Subject("dave", "beta", Set.of(Role.OWNER)));
        templates.register(new TemplateDescriptor("demo", "1", 3600, 1,
                Set.of("image", "api_key"), Set.of("api_key")));
        templates.register(new TemplateDescriptor("demo", "2", 3600, 1,
                Set.of("image", "api_key", "replicas"), Set.of("api_key")));
    }

    public Subject alice() { return directory.resolve("alice").orElseThrow(); }
    public Subject bob() { return directory.resolve("bob").orElseThrow(); }
    public Subject carol() { return directory.resolve("carol").orElseThrow(); }
    public Subject dave() { return directory.resolve("dave").orElseThrow(); }

    public ApplyCommand apply(Subject who, String name) {
        return new ApplyCommand(who, name, "demo", "1", null, 3600L);
    }

    public String envId(String tenant, String name) {
        return InMemoryEnvironmentRepository.deterministicId(tenant, name);
    }

    public Environment fresh(Subject who, String name) {
        return envs.apply(apply(who, name)).environment();
    }

    /** Simulate process restart: durable env state is restored; backend survives. */
    public void restart() {
        List<Environment> snapshot = List.copyOf(envRepo.snapshot());
        envRepo.restore(snapshot);
        quotas.recompute(envRepo.findAll());
    }
}
