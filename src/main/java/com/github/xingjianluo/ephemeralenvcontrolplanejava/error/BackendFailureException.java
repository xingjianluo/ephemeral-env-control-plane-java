package com.github.xingjianluo.ephemeralenvcontrolplanejava.error;

public class BackendFailureException extends ControlPlaneException {
    public BackendFailureException(String message) {
        super(ErrorCode.BACKEND_FAILURE, message);
    }

    public BackendFailureException(String message, Throwable cause) {
        super(ErrorCode.BACKEND_FAILURE, message, cause);
    }
}
