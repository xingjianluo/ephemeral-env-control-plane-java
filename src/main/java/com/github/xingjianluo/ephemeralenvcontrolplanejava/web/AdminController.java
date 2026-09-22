package com.github.xingjianluo.ephemeralenvcontrolplanejava.web;

import com.github.xingjianluo.ephemeralenvcontrolplanejava.service.AccessService;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.service.ReaperService;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.service.ReconcilerService;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.model.Subject;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/** Operator-only endpoints: bounded expiry sweep and post-restart convergence. */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final ReaperService reaperService;
    private final ReconcilerService reconcilerService;
    private final AccessService accessService;

    public AdminController(ReaperService reaperService,
                           ReconcilerService reconcilerService,
                           AccessService accessService) {
        this.reaperService = reaperService;
        this.reconcilerService = reconcilerService;
        this.accessService = accessService;
    }

    @PostMapping("/sweep")
    public Map<String, Integer> sweep(
            @RequestHeader(value = "X-Principal", required = false) String principal) {
        Subject subject = accessService.requireAuthenticated(principal);
        accessService.requireAdmin(subject);
        int recycled = reaperService.sweepOnce();
        Map<String, Integer> out = new LinkedHashMap<>();
        out.put("recycled", recycled);
        return out;
    }

    @PostMapping("/reconcile")
    public Map<String, Integer> reconcile(
            @RequestHeader(value = "X-Principal", required = false) String principal) {
        Subject subject = accessService.requireAuthenticated(principal);
        accessService.requireAdmin(subject);
        int orphans = reconcilerService.reconcileOrphans();
        int stuck = reconcilerService.reconcileStuckReleases();
        Map<String, Integer> out = new LinkedHashMap<>();
        out.put("orphansRemoved", orphans);
        out.put("stuckReleasesCompleted", stuck);
        return out;
    }
}
