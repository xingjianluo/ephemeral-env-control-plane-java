package com.github.xingjianluo.ephemeralenvcontrolplanejava.model;

import java.time.Instant;

/** Handle of a simulated backend resource allocated for an environment. */
public record ResourceHandle(String resourceId, String envId, String kind, Instant allocatedAt) {
}
