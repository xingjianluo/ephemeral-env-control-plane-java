package com.github.xingjianluo.ephemeralenvcontrolplanejava.error;

public class QuotaExceededException extends ControlPlaneException {
    public QuotaExceededException(String message) {
        super(ErrorCode.QUOTA_EXCEEDED, message);
    }

    public QuotaExceededException(String message, Throwable cause) {
        super(ErrorCode.QUOTA_EXCEEDED, message, cause);
    }
}
