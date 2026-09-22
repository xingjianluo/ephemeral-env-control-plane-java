package com.github.xingjianluo.ephemeralenvcontrolplanejava.config;

import java.time.Instant;

/** Clock abstraction so expiry scans and renewals are deterministic in tests. */
public interface TimeSource {
    Instant now();
}
