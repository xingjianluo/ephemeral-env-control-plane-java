package com.github.xingjianluo.ephemeralenvcontrolplanejava.repo;

import com.github.xingjianluo.ephemeralenvcontrolplanejava.model.Environment;

import java.util.List;
import java.util.Optional;

/** 环境存储。所有并发控制由 EnvironmentService 的锁层负责，仓库只做线程安全 Map 存取。 */
public interface EnvironmentRepository {

    Optional<Environment> find(String tenantId, String name);

    Optional<Environment> findById(String envId);

    void save(Environment env);

    /** 批量列表（带规模上限），返回不超过 limit 条。 */
    List<Environment> list(String tenantId, int limit);

    /** 全部环境分页（扫描/收敛用）：offset/limit 受限，不允许一次性全量加载。 */
    List<Environment> listAll(int offset, int limit);
}
