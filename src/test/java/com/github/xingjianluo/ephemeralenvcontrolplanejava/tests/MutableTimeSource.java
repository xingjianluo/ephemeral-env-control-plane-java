package com.github.xingjianluo.ephemeralenvcontrolplanejava.tests;

import com.github.xingjianluo.ephemeralenvcontrolplanejava.config.TimeSource;

import java.time.Instant;

/** Manually advanced clock so expiry/renewal races are deterministic. */
public class MutableTimeSource implements TimeSource {

    private volatile Instant now;

    public MutableTimeSource(Instant start) {
        this.now = start;
    }

    @Override
    public Instant now() {
        return now;
    }

    public void set(Instant t) {
        this.now = t;
    }

    public void advanceSeconds(long s) {
        this.now = now.plusSeconds(s);
    }
}
