package com.github.xingjianluo.ephemeralenvcontrolplanejava.web;

import com.github.xingjianluo.ephemeralenvcontrolplanejava.model.AuditRecord;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.model.Subject;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.service.AuditService;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.service.EnvironmentService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 审计查询。
 * - 带 envName：先解析环境并做 VIEW_ENV 鉴权（不存在 -> NOT_FOUND，越权 -> 403）；
 * - 不带 envName：仅限本租户记录（requireTenant），倒序、有上限。
 */
@RestController
public class AuditController {

    private final AuditService auditService;
    private final EnvironmentService environmentService;

    public AuditController(AuditService auditService, EnvironmentService environmentService) {
        this.auditService = auditService;
        this.environmentService = environmentService;
    }

    @GetMapping("/api/audit")
    public List<AuditRecord> query(Subject subject,
                                   @RequestParam(required = false) String envName,
                                   @RequestParam(defaultValue = "50") int limit) {
        if (envName != null) {
            return auditService.queryForEnv(
                    subject,
                    environmentService.get(subject, subject.tenantId(), envName),
                    limit);
        }
        return auditService.query(subject, subject.tenantId(), null, limit);
    }
}
