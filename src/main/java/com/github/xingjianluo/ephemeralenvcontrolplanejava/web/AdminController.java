package com.github.xingjianluo.ephemeralenvcontrolplanejava.web;

import com.github.xingjianluo.ephemeralenvcontrolplanejava.config.ControlPlaneProperties;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.model.Quota;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.service.LeaseExpiryScanner;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.service.QuotaService;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.service.Reconciler;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.web.dto.ReconcileReport;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 本地运维接口（本地模拟，无独立鉴权层；配额/扫描/收敛属于宿主侧管理动作）。
 * 扫描与收敛参数受配置上限约束：超过即采用配置上限/拒绝，不允许无界执行。
 */
@RestController
public class AdminController {

    private final QuotaService quotaService;
    private final LeaseExpiryScanner scanner;
    private final Reconciler reconciler;
    private final ControlPlaneProperties properties;

    public AdminController(QuotaService quotaService, LeaseExpiryScanner scanner,
                           Reconciler reconciler, ControlPlaneProperties properties) {
        this.quotaService = quotaService;
        this.scanner = scanner;
        this.reconciler = reconciler;
        this.properties = properties;
    }

    @PostMapping("/api/admin/quotas")
    public Map<String, String> setQuota(@RequestParam String tenantId,
                                        @RequestParam int maxEnvs,
                                        @RequestParam int maxCpu,
                                        @RequestParam int maxMemoryMb) {
        quotaService.setLimits(tenantId, maxEnvs, maxCpu, maxMemoryMb);
        return Map.of("status", "ok", "tenantId", tenantId);
    }

    @GetMapping("/api/admin/quotas")
    public Map<String, Object> quota(@RequestParam String tenantId) {
        Quota q = quotaService.getQuota(tenantId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tenantId", tenantId);
        out.put("maxEnvs", q.getMaxEnvs());
        out.put("maxCpu", q.getMaxCpu());
        out.put("maxMemoryMb", q.getMaxMemoryMb());
        out.put("usedEnvs", q.getUsedEnvs());
        out.put("usedCpu", q.getUsedCpu());
        out.put("usedMemoryMb", q.getUsedMemoryMb());
        return out;
    }

    @PostMapping("/api/admin/scan-expiries")
    public Map<String, Object> scan(@RequestParam(required = false) Integer maxBatch,
                                    @RequestParam(required = false) Long maxMillis) {
        int batch = clampBatch(maxBatch);
        long millis = clampMillis(maxMillis);
        int expired = scanner.scanOnce(batch, millis);
        return Map.of("expired", expired, "maxBatch", batch, "maxMillis", millis);
    }

    @PostMapping("/api/admin/reconcile")
    public ReconcileReport reconcile(@RequestParam(defaultValue = "50") int limit,
                                     @RequestParam(defaultValue = "200") long maxMillis) {
        return reconciler.reconcile(clampBatch(limit), clampMillis(maxMillis));
    }

    private int clampBatch(Integer requested) {
        int max = properties.getScan().getMaxBatch();
        if (requested == null) {
            return max;
        }
        return Math.min(requested, max);
    }

    private long clampMillis(Long requested) {
        long max = properties.getScan().getMaxMillis();
        if (requested == null) {
            return max;
        }
        return Math.min(requested, max);
    }
}
