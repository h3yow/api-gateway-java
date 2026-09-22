package com.github.h3yow.apigatewayjava.gateway.error;

/**
 * 网关内部统一异常，携带错误分类与建议重试时间。
 */
public class GatewayException extends RuntimeException {

    private final GatewayErrorCode errorCode;
    private final Long retryAfterMillis;

    public GatewayException(GatewayErrorCode errorCode, String message, Long retryAfterMillis) {
        super(message);
        this.errorCode = errorCode;
        this.retryAfterMillis = retryAfterMillis;
    }

    public GatewayException(GatewayErrorCode errorCode, String message, Long retryAfterMillis, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
        this.retryAfterMillis = retryAfterMillis;
    }

    public GatewayErrorCode errorCode() {
        return errorCode;
    }

    public Long retryAfterMillis() {
        return retryAfterMillis;
    }
}
