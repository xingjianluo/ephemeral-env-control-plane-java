package com.github.xingjianluo.ephemeralenvcontrolplanejava.error;

import org.springframework.http.HttpStatus;

/**
 * Stable, distinguishable error codes. Every rejected request maps to exactly
 * one code so callers can branch on the failure reason.
 */
public enum ErrorCode {
    VALIDATION_ERROR(HttpStatus.BAD_REQUEST),
    NAME_CONFLICT(HttpStatus.CONFLICT),
    QUOTA_EXCEEDED(HttpStatus.CONFLICT),
    ILLEGAL_TRANSITION(HttpStatus.CONFLICT),
    ENV_NOT_FOUND(HttpStatus.NOT_FOUND),
    TENANT_NOT_FOUND(HttpStatus.NOT_FOUND),
    TEMPLATE_NOT_FOUND(HttpStatus.BAD_REQUEST),
    UNSUPPORTED_TEMPLATE_VERSION(HttpStatus.BAD_REQUEST),
    UNSUPPORTED_TEMPLATE_FIELD(HttpStatus.BAD_REQUEST),
    FORBIDDEN(HttpStatus.FORBIDDEN),
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED),
    ENV_FAILED(HttpStatus.CONFLICT),
    EXPIRED(HttpStatus.CONFLICT),
    BACKEND_FAILURE(HttpStatus.SERVICE_UNAVAILABLE),
    BATCH_LIMIT_EXCEEDED(HttpStatus.PAYLOAD_TOO_LARGE);

    private final HttpStatus httpStatus;

    ErrorCode(HttpStatus httpStatus) {
        this.httpStatus = httpStatus;
    }

    public HttpStatus httpStatus() {
        return httpStatus;
    }
}
