package com.github.xingjianluo.ephemeralenvcontrolplanejava.config;

import com.github.xingjianluo.ephemeralenvcontrolplanejava.service.ReaperService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Drives the bounded expiry sweep. Each pass is independently bounded
 * (page size + time budget), so a large backlog spreads across ticks.
 */
@Component
public class ReaperScheduler {

    private static final Logger log = LoggerFactory.getLogger(ReaperScheduler.class);

    private final ReaperService reaperService;

    public ReaperScheduler(ReaperService reaperService) {
        this.reaperService = reaperService;
    }

    @Scheduled(fixedDelayString = "${cp.reaper-interval-millis:5000}")
    public void tick() {
        try {
            reaperService.sweepOnce();
        } catch (RuntimeException e) {
            // Never let the scheduled loop die; keep a distinguishable log line.
            log.error("scheduled reaper pass failed error='{}'", e.getMessage());
        }
    }
}
