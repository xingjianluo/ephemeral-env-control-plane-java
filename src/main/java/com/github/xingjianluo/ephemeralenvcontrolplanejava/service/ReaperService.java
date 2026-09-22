package com.github.xingjianluo.ephemeralenvcontrolplanejava.service;

/** Bounded, page-based expiry sweep; safe to run concurrently with renewals. */
public interface ReaperService {

    /** @return number of environments recycled by this pass. */
    int sweepOnce();
}
