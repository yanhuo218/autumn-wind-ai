package io.github.yanhuo218.autumnwind.gateway.web;

import java.util.Objects;

public final class GatewayException extends RuntimeException {

    private final GatewayErrorCode errorCode;

    public GatewayException(GatewayErrorCode errorCode, String message) {
        super(message);
        this.errorCode = Objects.requireNonNull(errorCode, "网关错误码不能为空。");
    }

    public GatewayException(GatewayErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = Objects.requireNonNull(errorCode, "网关错误码不能为空。");
    }

    public GatewayErrorCode errorCode() {
        return errorCode;
    }
}
