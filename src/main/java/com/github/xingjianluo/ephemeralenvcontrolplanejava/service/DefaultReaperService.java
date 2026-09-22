package com.github.xingjianluo.ephemeralenvcontrolplanejava.service;

import com.github.xingjianluo.ephemeralenvcontrolplanejava.config.ControlPlaneProperties;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.config.TimeSource;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.model.Environment;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.model.EnvStatus;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.repo.EnvironmentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;

/**
 * Bounded expiry sweep.
 *
 * <p>Bounds: at most {@code scanPageSize} candidates are touched per pass and
 * the pass stops once {@code scanBudgetMillis} elapses; the scheduler simply
 * runs another pass for the remainder. The candidate snapshot is a finite list
 * (never streamed unbounded). Each candidate is re-checked under its env lock
 * inside the service, so an environment renewed concurrently is never released.
 */
public class DefaultReaperService implements ReaperService {

    private static final Logger log = LoggerFactory.getLogger(DefaultReaperService.class);

    private final EnvironmentRepository repository;
    private final DefaultEnvironmentService environmentService;
    private final TimeSource timeSource;
    private final ControlPlaneProperties properties;

    public DefaultReaperService(EnvironmentRepository repository,
                                DefaultEnvironmentService environmentService,
                                TimeSource timeSource,
                                ControlPlaneProperties properties) {
        this.repository = repository;
        this.environmentService = environmentService;
        this.timeSource = timeSource;
        this.properties = properties;
    }

    @Override
    public int sweepOnce() {
        long deadline = System.nanoTime()
                + properties.getScanBudgetMillis() * 1_000_000L;
        int pageSize = properties.getScanPageSize();

        List<Environment> candidates = repository.findAll();
        int considered = 0;
        int recycled = 0;
        Instant now = timeSource.now();

        for (Environment candidate : candidates) {
            if (considered >= pageSize) {
                log.info("reaper page-limit hit page={} recycled={}; remainder deferred",
                        pageSize, recycled);
                break;
            }
            if (System.nanoTime() > deadline) {
                log.info("reaper time-budget hit considered={} recycled={}; remainder deferred",
                        considered, recycled);
                break;
            }
            considered++;
            if (candidate.status() == EnvStatus.EXPIRED) {
                // Already marked expired (e.g. by a losing renew); finish release.
                try {
                    environmentService.releaseAsSystem(candidate,
                            DefaultEnvironmentService.SYSTEM_REAPER, "lease expired");
                    recycled++;
                } catch (RuntimeException e) {
                    log.error("reaper failed to recycle expired env={} error='{}'",
                            candidate.id(), e.getMessage());
                }
                continue;
            }
            if (candidate.status() != EnvStatus.READY) {
                continue;
            }
            if (!candidate.lease().isExpiredAt(now)) {
                continue;
            }
            try {
                environmentService.releaseAsSystem(candidate,
                        DefaultEnvironmentService.SYSTEM_REAPER, "lease expired");
                recycled++;
            } catch (RuntimeException e) {
                // Distinguishable failure chain; one bad env never kills the sweep.
                log.error("reaper failed to recycle env={} error='{}'",
                        candidate.id(), e.getMessage());
            }
        }
        log.info("reaper pass complete considered={} recycled={}", considered, recycled);
        return recycled;
    }
}
