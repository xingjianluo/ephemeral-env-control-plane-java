package com.github.xingjianluo.ephemeralenvcontrolplanejava.error;

public class InvalidStateException extends ControlPlaneException {
    public InvalidStateException(String message) {
        super(ErrorCode.INVALID_STATE, message);
    }

    public InvalidStateException(String message, Throwable cause) {
        super(ErrorCode.INVALID_STATE, message, cause);
    }
}
