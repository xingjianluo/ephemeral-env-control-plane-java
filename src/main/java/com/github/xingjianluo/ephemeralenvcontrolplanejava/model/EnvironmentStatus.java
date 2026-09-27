package com.github.xingjianluo.ephemeralenvcontrolplanejava.model;

/**
 * 环境生命周期状态。状态机（实现阶段在 EnvironmentService 中强制）：
 * PENDING -> PROVISIONING -> READY | FAILED
 * READY -> RELEASING -> RELEASED（续期不改变 READY，只延长到期时间）
 * READY -> SUSPENDING -> SUSPENDED（挂起：释放计算资源与配额，保留身份与配置快照）
 * SUSPENDED -> RESUMING -> READY（恢复：重新占用配额并重建资源）
 * SUSPENDED -> RELEASING -> RELEASED（挂起中仍受有效期/最长保留约束，到期照常被回收）
 * SUSPENDING|RESUMING -> RELEASING -> RELEASED（中间态期间到期：请求临界区或收敛确定性回收）
 * FAILED -> RELEASING -> RELEASED（回收部分资源并归还配额）
 * SUSPENDING / RESUMING 为可查询的中间态，底层失败时停留于此，由收敛器推进。
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
