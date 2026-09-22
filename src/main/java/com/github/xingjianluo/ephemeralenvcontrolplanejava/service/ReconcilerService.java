package com.github.xingjianluo.ephemeralenvcontrolplanejava.service;

/** Converges orphan backend resources / interrupted state after restarts. */
public interface ReconcilerService {

    /** Deallocate backend resources unknown to any live environment. */
    int reconcileOrphans();

    /** Finish release of environments stuck in RELEASING (e.g. after crash). */
    int reconcileStuckReleases();
}
