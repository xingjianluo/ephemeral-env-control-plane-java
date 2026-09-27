package com.github.xingjianluo.ephemeralenvcontrolplanejava.service;

import com.github.xingjianluo.ephemeralenvcontrolplanejava.config.ControlPlaneProperties;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.model.Environment;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.model.EnvironmentStatus;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.repo.EnvironmentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 到期自动回收扫描器。
 *
 * 覆盖范围：READY 与 SUSPENDED 环境（挂起不免回收；SUSPENDED 还受最长保留时间约束，
 * 判定逻辑在 EnvironmentService.expireIfDue 内统一完成）。
 * 有界性：每轮最多处理 maxBatch 个环境、总耗时不超过 maxMillis；
 * 超过任一上限即停止本轮（分页推进），由下一轮继续，绝不无界占用内存或线程。
 * 正确性：到期判定与回收动作在每个环境的租户锁内原子完成
 * （见 EnvironmentService.expireIfDue），与并发续期/恢复串行，
 * “续期/恢复成功”与“被回收”只有一个结果，不存在半更新。
 */
@Component
public class LeaseExpiryScanner {

    private static final Logger log = LoggerFactory.getLogger(LeaseExpiryScanner.class);
    private static final int PAGE_SIZE = 20;

    private final EnvironmentRepository repository;
    private final EnvironmentService environmentService;
    private final ControlPlaneProperties properties;

    public LeaseExpiryScanner(EnvironmentRepository repository,
                              EnvironmentService environmentService,
                              ControlPlaneProperties properties) {
        this.repository = repository;
        this.environmentService = environmentService;
        this.properties = properties;
    }

    public int scanOnce(int maxBatch, long maxMillis) {
        if (maxBatch <= 0 || maxMillis <= 0) {
            return 0;
        }
        long deadline = System.currentTimeMillis() + maxMillis;
        int processed = 0;
        int expired = 0;
        int offset = 0;
        while (processed < maxBatch && System.currentTimeMillis() < deadline) {
            List<Environment> page = repository.listAll(offset, PAGE_SIZE);
            if (page.isEmpty()) {
                break;
            }
            for (Environment env : page) {
                if (processed >= maxBatch || System.currentTimeMillis() >= deadline) {
                    break;
                }
                processed++;
                if ((env.getStatus() == EnvironmentStatus.READY
                        || env.getStatus() == EnvironmentStatus.SUSPENDED)
                        && environmentService.expireIfDue(env)) {
                    expired++;
                }
            }
            offset += page.size();
            if (page.size() < PAGE_SIZE) {
                break;
            }
        }
        log.info("expiry scan done scanned={} expired={} truncated={}",
                processed, expired, processed >= maxBatch);
        return expired;
    }

    @Scheduled(fixedDelayString = "${ecp.lease.scan-interval-ms:1000}")
    public void scheduledScan() {
        if (!properties.getLease().isScanEnabled()) {
            return;
        }
        scanOnce(properties.getScan().getMaxBatch(), properties.getScan().getMaxMillis());
    }
}
