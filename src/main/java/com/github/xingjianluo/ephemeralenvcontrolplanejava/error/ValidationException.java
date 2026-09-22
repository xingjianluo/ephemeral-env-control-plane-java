package com.github.xingjianluo.ephemeralenvcontrolplanejava.error;

public class ValidationException extends ControlPlaneException {
    public ValidationException(String message) {
        super(ErrorCode.VALIDATION, message);
    }

    public ValidationException(String message, Throwable cause) {
        super(ErrorCode.VALIDATION, message, cause);
    }
}
