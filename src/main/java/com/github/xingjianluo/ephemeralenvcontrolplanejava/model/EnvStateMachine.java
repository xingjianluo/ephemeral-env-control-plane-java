package com.github.xingjianluo.ephemeralenvcontrolplanejava.model;

import com.github.xingjianluo.ephemeralenvcontrolplanejava.error.ApiException;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.error.ErrorCode;

import java.util.Map;
import java.util.Set;

/** Legal lifecycle transitions. States never jump outside this table. */
public final class EnvStateMachine {

    private static final Map<EnvStatus, Set<EnvStatus>> ALLOWED = Map.of(
            EnvStatus.REQUESTED, Set.of(EnvStatus.PROVISIONING, EnvStatus.FAILED, EnvStatus.RELEASING),
            EnvStatus.PROVISIONING, Set.of(EnvStatus.READY, EnvStatus.FAILED, EnvStatus.RELEASING),
            EnvStatus.READY, Set.of(EnvStatus.EXPIRED, EnvStatus.RELEASING),
            EnvStatus.FAILED, Set.of(EnvStatus.RELEASING),
            EnvStatus.RELEASING, Set.of(EnvStatus.RELEASED),
            EnvStatus.RELEASED, Set.of(),
            EnvStatus.EXPIRED, Set.of(EnvStatus.RELEASING));

    private EnvStateMachine() {
    }

    public static void requireTransition(EnvStatus from, EnvStatus to) {
        if (!ALLOWED.getOrDefault(from, Set.of()).contains(to)) {
            throw new ApiException(ErrorCode.ILLEGAL_TRANSITION,
                    "illegal state transition: " + from + " -> " + to);
        }
    }
}
