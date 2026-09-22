package com.github.xingjianluo.ephemeralenvcontrolplanejava.error;

public class UnauthorizedException extends ControlPlaneException {
    public UnauthorizedException(String message) {
        super(ErrorCode.UNAUTHORIZED, message);
    }

    public UnauthorizedException(String message, Throwable cause) {
        super(ErrorCode.UNAUTHORIZED, message, cause);
    }
}
