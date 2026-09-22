package com.github.xingjianluo.ephemeralenvcontrolplanejava.error;

/**
 * 可区分的拒绝原因。映射为 HTTP 状态：
 * 404 NOT_FOUND / FORBIDDEN；400 VALIDATION / TEMPLATE；409 CONFLICT / STATE / QUOTA / NAME_CONFLICT；
 * 429 BATCH_LIMIT；503 BACKEND。
 */
public enum ErrorCode {
    VALIDATION,
    NAME_CONFLICT,
    QUOTA_EXCEEDED,
    TEMPLATE_INCOMPATIBLE,
    UNAUTHORIZED,
    FORBIDDEN,
    NOT_FOUND,
    INVALID_STATE,
    BACKEND_FAILURE,
    BATCH_LIMIT
}
