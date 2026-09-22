package com.github.xingjianluo.ephemeralenvcontrolplanejava.backend;

/**
 * Failure raised by the simulated resource backend. Analogous to a cluster/cloud
 * API error during allocation. Carries no sensitive data.
 */
public class BackendException extends RuntimeException {

    public BackendException(String message) {
        super(message);
    }
}
