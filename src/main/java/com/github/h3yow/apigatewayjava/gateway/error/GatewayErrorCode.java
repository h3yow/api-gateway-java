package com.github.h3yow.apigatewayjava.gateway.error;

/**
 * 对外可区分的错误分类及其 HTTP 状态。
 */
public enum GatewayErrorCode {
    LOCAL_RATE_LIMITED,
    DOWNSTREAM_RATE_LIMITED,
    VERSION_MISSING,
    VERSION_UNKNOWN,
    VERSION_NOT_SUPPORTED,
    DOWNSTREAM_TIMEOUT,
    DOWNSTREAM_UNAVAILABLE,
    DOWNSTREAM_ERROR,
    INTERNAL_ERROR;

    public int httpStatus() {
        return switch (this) {
            case LOCAL_RATE_LIMITED, DOWNSTREAM_RATE_LIMITED -> 429;
            case VERSION_MISSING, VERSION_UNKNOWN, VERSION_NOT_SUPPORTED -> 400;
            case DOWNSTREAM_TIMEOUT, DOWNSTREAM_UNAVAILABLE -> 504;
            case DOWNSTREAM_ERROR -> 502;
            case INTERNAL_ERROR -> 500;
        };
    }
}
