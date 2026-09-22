package com.github.h3yow.apigatewayjava.gateway.model;

/**
 * 网关拒绝/错误原因枚举。每种原因对应稳定的错误码与 HTTP 状态，保证对外结论可区分、可解释。
 */
public enum RejectReason {

    MISSING_CALLER("GATEWAY_MISSING_CALLER", 401),
    UNKNOWN_CALLER_DEFAULT("GATEWAY_UNKNOWN_CALLER_DEFAULT", 200),
    MISSING_VERSION("GATEWAY_MISSING_VERSION", 400),
    UNKNOWN_VERSION("GATEWAY_UNKNOWN_VERSION", 400),
    CALLER_VERSION_NOT_ALLOWED("GATEWAY_CALLER_VERSION_NOT_ALLOWED", 400),
    RATE_LIMITED_QUOTA_EXHAUSTED("GATEWAY_RATE_LIMITED", 429),
    DOWNSTREAM_TIMEOUT("GATEWAY_DOWNSTREAM_TIMEOUT", 504),
    DOWNSTREAM_RATE_LIMITED("GATEWAY_DOWNSTREAM_RATE_LIMITED", 429),
    DOWNSTREAM_ERROR("GATEWAY_DOWNSTREAM_ERROR", 502);

    private final String code;
    private final int httpStatus;

    RejectReason(String code, int httpStatus) {
        this.code = code;
        this.httpStatus = httpStatus;
    }

    public String code() {
        return code;
    }

    public int httpStatus() {
        return httpStatus;
    }
}
