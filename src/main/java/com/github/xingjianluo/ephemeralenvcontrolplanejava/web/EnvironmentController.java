package com.github.xingjianluo.ephemeralenvcontrolplanejava.web;

import com.github.xingjianluo.ephemeralenvcontrolplanejava.error.ApiException;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.error.ErrorCode;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.model.Environment;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.model.Subject;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.service.AccessService;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.service.ApplyCommand;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.service.ApplyResult;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.service.AuditService;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.service.EnvironmentService;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.web.dto.ApplyRequest;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.web.dto.AuditView;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.web.dto.EnvView;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.web.dto.RenewRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/tenants/{tenantId}/envs")
public class EnvironmentController {

    private final EnvironmentService environmentService;
    private final AuditService auditService;
    private final AccessService accessService;

    public EnvironmentController(EnvironmentService environmentService,
                                 AuditService auditService,
                                 AccessService accessService) {
        this.environmentService = environmentService;
        this.auditService = auditService;
        this.accessService = accessService;
    }

    @PostMapping
    public ResponseEntity<EnvView> apply(@RequestHeader(value = "X-Principal", required = false)
                                         String principal,
                                         @PathVariable String tenantId,
                                         @RequestBody ApplyRequest request) {
        Subject subject = accessService.requireAuthenticated(principal);
        if (!subject.tenantId().equals(tenantId)) {
            throw new ApiException(ErrorCode.FORBIDDEN, "access denied");
        }
        if (request == null) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "request body required");
        }
        ApplyResult result = environmentService.apply(new ApplyCommand(
                subject, request.name(), request.templateId(), request.version(),
                request.parameters(), request.ttlSeconds()));

        Environment env = result.environment();
        // Only the owner gets secrets back on the creating response.
        EnvView view = EnvViewMapper.toView(env,
                subject.principal().equals(env.owner()));
        // A FAILED provision is reported with 409, never 201 success.
        if (result.created() && "FAILED".equals(env.status().name())) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(view);
        }
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(view);
    }

    @GetMapping
    public List<EnvView> list(@RequestHeader(value = "X-Principal", required = false) String principal,
                              @PathVariable String tenantId,
                              @RequestParam(defaultValue = "50") int limit) {
        return environmentService.list(principal, tenantId, limit).stream()
                .map(e -> EnvViewMapper.toView(e, false))
                .toList();
    }

    @GetMapping("/{envId}")
    public EnvView get(@RequestHeader(value = "X-Principal", required = false) String principal,
                       @PathVariable String tenantId,
                       @PathVariable String envId,
                       @RequestParam(defaultValue = "false") boolean secrets) {
        Environment env = environmentService.get(principal, tenantId, envId, secrets);
        return EnvViewMapper.toView(env, secrets);
    }

    @PostMapping("/{envId}/renew")
    public EnvView renew(@RequestHeader(value = "X-Principal", required = false) String principal,
                         @PathVariable String tenantId,
                         @PathVariable String envId,
                         @RequestBody(required = false) RenewRequest request) {
        Long ttl = request == null ? null : request.ttlSeconds();
        Environment env = environmentService.renew(principal, tenantId, envId, ttl);
        return EnvViewMapper.toView(env, false);
    }

    @DeleteMapping("/{envId}")
    public ResponseEntity<Void> recycle(@RequestHeader(value = "X-Principal", required = false)
                                        String principal,
                                        @PathVariable String tenantId,
                                        @PathVariable String envId,
                                        @RequestParam(defaultValue = "requested") String reason) {
        environmentService.recycle(principal, tenantId, envId, reason);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{envId}/audit")
    public List<AuditView> audit(@RequestHeader(value = "X-Principal", required = false) String principal,
                                 @PathVariable String tenantId,
                                 @PathVariable String envId,
                                 @RequestParam(defaultValue = "0") int offset,
                                 @RequestParam(defaultValue = "50") int limit) {
        // Existence + permission are enforced by resolving the env first.
        environmentService.get(principal, tenantId, envId, false);
        if (offset < 0 || limit <= 0 || limit > 200) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR,
                    "offset must be >=0 and limit within 1..200");
        }
        return auditService.query(tenantId, envId, offset, limit).stream()
                .map(a -> new AuditView(a.id(), a.at(), a.envId(), a.actor(),
                        a.action().name(),
                        a.fromStatus() == null ? null : a.fromStatus().name(),
                        a.toStatus() == null ? null : a.toStatus().name(),
                        a.reason()))
                .toList();
    }
}
