package com.github.xingjianluo.ephemeralenvcontrolplanejava.model;

/**
 * 环境生命周期状态。状态机（实现阶段在 EnvironmentService 中强制）：
 * PENDING -> PROVISIONING -> READY | FAILED
 * READY -> RELEASING -> RELEASED（续期不改变 READY，只延长到期时间）
 * FAILED -> RELEASING -> RELEASED（回收部分资源并归还配额）
 * RELEASED 为终态，回收幂等。
 */
public enum EnvironmentStatus {
    PENDING,
    PROVISIONING,
    READY,
    FAILED,
    RELEASING,
    RELEASED
}
