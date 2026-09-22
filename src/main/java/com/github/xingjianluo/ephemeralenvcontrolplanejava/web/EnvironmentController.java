package com.github.xingjianluo.ephemeralenvcontrolplanejava.web;

import com.github.xingjianluo.ephemeralenvcontrolplanejava.model.Environment;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.model.Subject;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.service.AuditService;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.service.EnvironmentService;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.web.dto.ApplyRequest;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.web.dto.EnvironmentResponse;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.web.dto.RenewRequest;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** 环境生命周期 REST 接口。所有接口通过 Subject 头解析访问主体并做租户/角色/所有者鉴权。 */
@RestController
public class EnvironmentController {

    private final EnvironmentService environmentService;
    private final AuditService auditService;

    public EnvironmentController(EnvironmentService environmentService, AuditService auditService) {
        this.environmentService = environmentService;
        this.auditService = auditService;
    }

    @PostMapping("/api/environments")
    public EnvironmentResponse apply(Subject subject, @RequestBody ApplyRequest request) {
        // 幂等语义：首次创建与同名同参数重复申请都返回同一环境的当前表示（统一 200）。
        // 创建过程中的部分失败不返回成功响应，而由全局异常处理映射为 503 + FAILED 中间态可查。
        return EnvironmentResponse.of(environmentService.apply(subject, request));
    }

    @GetMapping("/api/environments/{name}")
    public EnvironmentResponse get(Subject subject, @PathVariable String name,
                                   @RequestParam String tenantId) {
        return EnvironmentResponse.of(environmentService.get(subject, tenantId, name));
    }

    @GetMapping("/api/environments")
    public List<EnvironmentResponse> list(Subject subject,
                                          @RequestParam(defaultValue = "50") int limit) {
        return environmentService.list(subject, limit).stream()
                .map(EnvironmentResponse::of)
                .toList();
    }

    @PostMapping("/api/environments/{name}/renew")
    public EnvironmentResponse renew(Subject subject, @PathVariable String name,
                                     @RequestBody RenewRequest request) {
        if (request.getTenantId() == null || request.getTtlSeconds() == null) {
            throw new com.github.xingjianluo.ephemeralenvcontrolplanejava.error.ValidationException(
                    "tenantId and ttlSeconds are required");
        }
        return EnvironmentResponse.of(
                environmentService.renew(subject, request.getTenantId(), name, request.getTtlSeconds()));
    }

    @DeleteMapping("/api/environments/{name}")
    public EnvironmentResponse release(Subject subject, @PathVariable String name,
                                       @RequestParam String tenantId,
                                       @RequestParam(required = false) String reason) {
        return EnvironmentResponse.of(
                environmentService.release(subject, tenantId, name, reason));
    }

    @GetMapping("/api/environments/{name}/secret")
    public Map<String, String> secret(Subject subject, @PathVariable String name,
                                      @RequestParam String tenantId) {
        String value = environmentService.getSecret(subject, tenantId, name);
        return Map.of("secret", value);
    }
}
