package com.github.xingjianluo.ephemeralenvcontrolplanejava.service;

import com.github.xingjianluo.ephemeralenvcontrolplanejava.error.QuotaExceededException;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.error.ValidationException;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.model.Quota;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.repo.InMemoryQuotaRepository;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 多租户配额服务。
 * lockFor(tenantId) 返回租户级互斥锁：EnvironmentService 保证
 * “同租户的申请/回收/续期判定”都在该锁内，配合仓库的条件落库
 * （absent/present）消除 check-then-act 竞争，杜绝超额分配。
 */
@Service
public class QuotaService {

    private final InMemoryQuotaRepository quotaRepository;
    private final Map<String, Object> tenantLocks = new ConcurrentHashMap<>();

    public QuotaService(InMemoryQuotaRepository quotaRepository) {
        this.quotaRepository = quotaRepository;
    }

    /** 同租户写操作必须先获取该锁（在整个生命周期临界区内持有）。 */
    public Object lockFor(String tenantId) {
        return tenantLocks.computeIfAbsent(tenantId, k -> new Object());
    }

    public void setLimits(String tenantId, int maxEnvs, int maxCpu, int maxMemoryMb) {
        if (maxEnvs < 0 || maxCpu < 0 || maxMemoryMb < 0) {
            throw new ValidationException("quota limits must be non-negative");
        }
        synchronized (lockFor(tenantId)) {
            Quota q = quotaRepository.getOrCreate(tenantId);
            q.setMaxEnvs(maxEnvs);
            q.setMaxCpu(maxCpu);
            q.setMaxMemoryMb(maxMemoryMb);
            quotaRepository.save(tenantId, q);
        }
    }

    public Quota getQuota(String tenantId) {
        synchronized (lockFor(tenantId)) {
            return quotaRepository.getOrCreate(tenantId);
        }
    }

    /** 原子检查并占用；不足抛 QuotaExceededException（原因可区分）。调用方必须持有租户锁。 */
    public void reserve(String tenantId, int cpu, int memoryMb) {
        Quota q = quotaRepository.getOrCreate(tenantId);
        String rejected = q.tryReserve(cpu, memoryMb);
        if (rejected != null) {
            throw new QuotaExceededException(rejected + " (tenant=" + tenantId + ")");
        }
        quotaRepository.save(tenantId, q);
    }

    /** 归还占用。调用方必须持有租户锁，且环境状态机保证不会重复归还。 */
    public void release(String tenantId, int cpu, int memoryMb) {
        Quota q = quotaRepository.getOrCreate(tenantId);
        q.release(cpu, memoryMb);
        quotaRepository.save(tenantId, q);
    }
}
