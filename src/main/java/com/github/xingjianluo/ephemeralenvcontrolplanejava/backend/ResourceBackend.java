package com.github.xingjianluo.ephemeralenvcontrolplanejava.backend;

import com.github.xingjianluo.ephemeralenvcontrolplanejava.model.ResourceSpec;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.model.SimResource;

import java.util.List;

/**
 * 模拟资源后端（代替真实集群/云平台）。
 * 支持注入分配/释放失败以制造部分失败场景；释放不存在的资源视为成功（幂等）。
 */
public interface ResourceBackend {

    /** 分配一个资源。失败抛出 BackendFailureException 且不留下资源。 */
    SimResource allocate(String envId, ResourceSpec spec);

    /** 释放一个资源；资源不存在返回 false（调用方据此做收敛判定），已存在则删除并返回 true。 */
    boolean release(String resourceId);

    /** 释放某环境的全部资源，返回实际删除数量；可注入部分失败。 */
    int releaseAll(String envId);

    /** 查询某环境当前残留的资源（收敛孤立资源用）。 */
    List<SimResource> findByEnv(String envId);

    /** 全量资源分页（收敛扫描用）：offset/limit 受限，不允许一次性全量加载。 */
    List<SimResource> listAll(int offset, int limit);
}
