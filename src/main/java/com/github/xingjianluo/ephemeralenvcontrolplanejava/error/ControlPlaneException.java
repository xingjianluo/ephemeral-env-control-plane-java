package com.github.xingjianluo.ephemeralenvcontrolplanejava.error;

/** 控制平面异常基类：携带可区分的错误码与不含敏感信息的消息。 */
public class ControlPlaneException extends RuntimeException {
    private final ErrorCode code;

    public ControlPlaneException(ErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public ControlPlaneException(ErrorCode code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public ErrorCode getCode() {
        return code;
    }
}
