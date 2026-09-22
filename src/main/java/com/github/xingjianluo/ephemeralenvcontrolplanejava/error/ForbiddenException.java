package com.github.xingjianluo.ephemeralenvcontrolplanejava.error;

public class ForbiddenException extends ControlPlaneException {
    public ForbiddenException(String message) {
        super(ErrorCode.FORBIDDEN, message);
    }

    public ForbiddenException(String message, Throwable cause) {
        super(ErrorCode.FORBIDDEN, message, cause);
    }
}
