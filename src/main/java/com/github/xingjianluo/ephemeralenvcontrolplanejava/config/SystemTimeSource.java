package com.github.xingjianluo.ephemeralenvcontrolplanejava.config;

import java.time.Instant;

public class SystemTimeSource implements TimeSource {
    @Override
    public Instant now() {
        return Instant.now();
    }
}
