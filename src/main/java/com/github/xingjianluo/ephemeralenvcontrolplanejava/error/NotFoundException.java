package com.github.xingjianluo.ephemeralenvcontrolplanejava.error;

public class NotFoundException extends ControlPlaneException {
    public NotFoundException(String message) {
        super(ErrorCode.NOT_FOUND, message);
    }

    public NotFoundException(String message, Throwable cause) {
        super(ErrorCode.NOT_FOUND, message, cause);
    }
}
