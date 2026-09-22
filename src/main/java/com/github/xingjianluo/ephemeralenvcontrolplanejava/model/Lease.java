package com.github.xingjianluo.ephemeralenvcontrolplanejava.model;

import java.time.Instant;

/** Immutable lease window of an environment. */
public record Lease(Instant acquiredAt, Instant expiresAt) {

    public boolean isExpiredAt(Instant now) {
        return !now.isBefore(expiresAt);
    }

    public Lease withExpiry(Instant newExpiry) {
        return new Lease(acquiredAt, newExpiry);
    }
}
