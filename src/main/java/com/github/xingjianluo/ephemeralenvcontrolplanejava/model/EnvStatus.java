package com.github.xingjianluo.ephemeralenvcontrolplanejava.model;

/**
 * Environment lifecycle states. Legal transitions are enforced by the state machine.
 */
public enum EnvStatus {
    REQUESTED,
    PROVISIONING,
    READY,
    FAILED,
    RELEASING,
    RELEASED,
    EXPIRED;

    public boolean isTerminal() {
        return this == RELEASED || this == EXPIRED;
    }

    /**
     * States that still consume quota/backend resources. EXPIRED keeps holding
     * both until the reaper completes the release — expiry itself is not yet
     * recycling, so counting it here prevents ghost-occupancy window.
     */
    public boolean holdsQuota() {
        return this == REQUESTED || this == PROVISIONING || this == READY
                || this == EXPIRED || this == RELEASING;
    }
}
