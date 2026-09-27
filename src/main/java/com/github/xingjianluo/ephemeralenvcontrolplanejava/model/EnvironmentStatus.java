package com.github.xingjianluo.ephemeralenvcontrolplanejava.model;

/**
 * 环境生命周期状态。状态机（实现阶段在 EnvironmentService 中强制）：
 * PENDING -> PROVISIONING -> READY | FAILED
 * READY -> RELEASING -> RELEASED（续期不改变 READY，只延长到期时间）
 * FAILED -> RELEASING -> RELEASED（回收部分资源并归还配额）
 * READY -> SUSPENDING -> SUSPENDED（挂起：释放后端计算资源并归还配额，保留环境本体与配置快照）
 * SUSPENDED -> RESUMING -> READY（恢复：重新占用配额并按固化规格重建资源）
 * RESUMING -> SUSPENDED（恢复失败/收敛回滚，稳定停在挂起态以便重试）
 * SUSPENDED -> RELEASING -> RELEASED（挂起不免回收：到期或超保留期仍被清理）
 * RELEASED 为终态，回收幂等。
 */
public enum EnvironmentStatus {
    PENDING,
    PROVISIONING,
    READY,
    FAILED,
    SUSPENDING,
    SUSPENDED,
    RESUMING,
    RELEASING,
    RELEASED
}
