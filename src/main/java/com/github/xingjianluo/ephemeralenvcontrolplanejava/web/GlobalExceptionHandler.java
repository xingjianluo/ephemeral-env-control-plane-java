package com.github.xingjianluo.ephemeralenvcontrolplanejava.web;

import com.github.xingjianluo.ephemeralenvcontrolplanejava.error.ControlPlaneException;
import com.github.xingjianluo.ephemeralenvcontrolplanejava.error.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * 异常 -> HTTP 响应映射：错误码可区分，异常链路不被吞掉。
 * 响应体只含 error/message，message 由服务端构造，不回显请求中的敏感字段。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ControlPlaneException.class)
    public ResponseEntity<Map<String, String>> handle(ControlPlaneException ex) {
        HttpStatus status = statusOf(ex.getCode());
        // 4xx 打印摘要即可；5xx 保留完整链路（消息本身不含敏感值）。
        if (status.is5xxServerError()) {
            log.warn("control-plane failure code={} message={}", ex.getCode(), ex.getMessage(), ex);
        } else {
            log.info("request rejected code={} message={}", ex.getCode(), ex.getMessage());
        }
        return ResponseEntity.status(status)
                .body(Map.of("error", ex.getCode().name(), "message",
                        String.valueOf(ex.getMessage())));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, String>> handleUnreadable(HttpMessageNotReadableException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("error", ErrorCode.VALIDATION.name(), "message", "malformed request body"));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleIllegalArg(IllegalArgumentException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("error", ErrorCode.VALIDATION.name(), "message",
                        String.valueOf(ex.getMessage())));
    }

    private HttpStatus statusOf(ErrorCode code) {
        return switch (code) {
            case VALIDATION, TEMPLATE_INCOMPATIBLE -> HttpStatus.BAD_REQUEST;
            case UNAUTHORIZED -> HttpStatus.UNAUTHORIZED;
            case FORBIDDEN -> HttpStatus.FORBIDDEN;
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case NAME_CONFLICT, INVALID_STATE, QUOTA_EXCEEDED -> HttpStatus.CONFLICT;
            case BATCH_LIMIT -> HttpStatus.TOO_MANY_REQUESTS;
            case BACKEND_FAILURE -> HttpStatus.SERVICE_UNAVAILABLE;
        };
    }
}
