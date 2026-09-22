package com.github.xingjianluo.ephemeralenvcontrolplanejava.error;

public class BatchLimitExceededException extends ControlPlaneException {
    public BatchLimitExceededException(String message) {
        super(ErrorCode.BATCH_LIMIT, message);
    }

    public BatchLimitExceededException(String message, Throwable cause) {
        super(ErrorCode.BATCH_LIMIT, message, cause);
    }
}
