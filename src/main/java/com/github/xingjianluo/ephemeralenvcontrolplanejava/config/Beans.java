package com.github.xingjianluo.ephemeralenvcontrolplanejava.config;

import com.github.xingjianluo.ephemeralenvcontrolplanejava.backend.ResourceBackend;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.backend.SimulatedResourceBackend;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.repo.AuditRepository;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.repo.EnvironmentRepository;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.repo.InMemoryAuditRepository;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.repo.InMemoryEnvironmentRepository;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.service.*;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(ControlPlaneProperties.class)
public class Beans {

    @Bean
    public TimeSource timeSource() {
        return new SystemTimeSource();
    }

    @Bean
    public EnvironmentRepository environmentRepository() {
        return new InMemoryEnvironmentRepository();
    }

    @Bean
    public AuditRepository auditRepository() {
        return new InMemoryAuditRepository();
    }

    @Bean
    public ResourceBackend resourceBackend(TimeSource timeSource) {
        return new SimulatedResourceBackend(timeSource);
    }

    @Bean
    public SubjectDirectory subjectDirectory() {
        return new InMemorySubjectDirectory();
    }

    @Bean
    public QuotaService quotaService() {
        return new DefaultQuotaService();
    }

    @Bean
    public TemplateService templateService() {
        return new DefaultTemplateService();
    }

    @Bean
    public AuditService auditService(AuditRepository auditRepository, TimeSource timeSource) {
        return new DefaultAuditService(auditRepository, timeSource);
    }

    @Bean
    public AccessService accessService(SubjectDirectory subjectDirectory) {
        return new DefaultAccessService(subjectDirectory);
    }

    @Bean
    public EnvironmentService environmentService(EnvironmentRepository environmentRepository,
                                                 TemplateService templateService,
                                                 QuotaService quotaService,
                                                 AuditService auditService,
                                                 AccessService accessService,
                                                 ResourceBackend resourceBackend,
                                                 TimeSource timeSource,
                                                 ControlPlaneProperties properties) {
        return new DefaultEnvironmentService(environmentRepository, templateService, quotaService,
                auditService, accessService, resourceBackend, timeSource, properties);
    }

    @Bean
    public ReaperService reaperService(EnvironmentRepository environmentRepository,
                                       EnvironmentService environmentService,
                                       TimeSource timeSource,
                                       ControlPlaneProperties properties) {
        return new DefaultReaperService(environmentRepository,
                (DefaultEnvironmentService) environmentService, timeSource, properties);
    }

    @Bean
    public ReconcilerService reconcilerService(EnvironmentRepository environmentRepository,
                                               ResourceBackend resourceBackend,
                                               QuotaService quotaService,
                                               AuditService auditService,
                                               TimeSource timeSource,
                                               ControlPlaneProperties properties) {
        return new DefaultReconcilerService(environmentRepository, resourceBackend, quotaService,
                auditService, timeSource, properties);
    }
}
