package com.github.xingjianluo.ephemeralenvcontrolplanejava.error;

public class TemplateIncompatibleException extends ControlPlaneException {
    public TemplateIncompatibleException(String message) {
        super(ErrorCode.TEMPLATE_INCOMPATIBLE, message);
    }

    public TemplateIncompatibleException(String message, Throwable cause) {
        super(ErrorCode.TEMPLATE_INCOMPATIBLE, message, cause);
    }
}
