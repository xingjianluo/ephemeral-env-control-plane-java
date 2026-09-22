package com.github.xingjianluo.ephemeralenvcontrolplanejava.error;

public class NameConflictException extends ControlPlaneException {
    public NameConflictException(String message) {
        super(ErrorCode.NAME_CONFLICT, message);
    }

    public NameConflictException(String message, Throwable cause) {
        super(ErrorCode.NAME_CONFLICT, message, cause);
    }
}
