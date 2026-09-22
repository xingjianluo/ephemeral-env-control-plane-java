package com.github.xingjianluo.ephemeralenvcontrolplanejava.service;

import com.github.xingjianluo.ephemeralenvcontrolplanejava.model.Environment;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.model.Subject;

import java.util.List;

/** Application/ready/renew/recycle orchestration. */
public interface EnvironmentService {

    ApplyResult apply(ApplyCommand command);

    Environment get(String principal, String tenantId, String envId, boolean includeSecrets);

    Environment renew(String principal, String tenantId, String envId, Long requestedTtlSeconds);

    void recycle(String principal, String tenantId, String envId, String reason);

    List<Environment> list(String principal, String tenantId, int limit);
}
