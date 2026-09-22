package com.github.xingjianluo.ephemeralenvcontrolplanejava.testsupport;

import com.github.xingjianluo.ephemeralenvcontrolplanejava.model.Role;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.model.Subject;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.model.TenantQuota;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.service.InMemorySubjectDirectory;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.service.QuotaService;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.service.SubjectDirectory;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.service.TemplateService;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.template.TemplateDescriptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Seeds deterministic local data so the control plane is verifiable with no
 * external identity provider:
 *
 * <pre>
 * tenants: alpha (owner: alice, member: bob, viewer: carol), beta (owner: dave)
 * quota:   alpha 3 envs / 6 units, beta 2 envs / 4 units
 * templates: demo@1 (image, api_key), demo@2 (+ replicas, default 1)
 * </pre>
 */
@Component
public class DataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DataSeeder.class);

    private final SubjectDirectory subjectDirectory;
    private final QuotaService quotaService;
    private final TemplateService templateService;

    public DataSeeder(SubjectDirectory subjectDirectory, QuotaService quotaService,
                      TemplateService templateService) {
        this.subjectDirectory = subjectDirectory;
        this.quotaService = quotaService;
        this.templateService = templateService;
    }

    @Override
    public void run(ApplicationArguments args) {
        quotaService.setQuota(new TenantQuota("alpha", 3, 6));
        quotaService.setQuota(new TenantQuota("beta", 2, 4));

        InMemorySubjectDirectory dir = (InMemorySubjectDirectory) subjectDirectory;
        dir.register(new Subject("alice", "alpha", Set.of(Role.OWNER)));
        dir.register(new Subject("bob", "alpha", Set.of(Role.MEMBER)));
        dir.register(new Subject("carol", "alpha", Set.of(Role.VIEWER)));
        dir.register(new Subject("dave", "beta", Set.of(Role.OWNER)));

        templateService.register(new TemplateDescriptor(
                "demo", "1", 3600, 1,
                Set.of("image", "api_key"), Set.of("api_key")));
        // v2 adds 'replicas'; absent means 1, preserving v1 semantics.
        templateService.register(new TemplateDescriptor(
                "demo", "2", 3600, 1,
                Set.of("image", "api_key", "replicas"), Set.of("api_key")));

        log.info("seeded tenants alpha/beta, subjects alice,bob,carol,dave, template demo@1/@2");
    }
}
