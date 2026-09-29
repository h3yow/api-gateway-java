package com.github.h3yow.apigatewayjava.gateway.model;

/**
 * 网关拒绝/错误原因枚举。每种原因对应稳定的错误码与 HTTP 状态，并显式标注
 * {@link ErrorSource 错误来源}，保证对外结论可区分、可解释：
 * <ul>
 *     <li>LOCAL —— 请求还没进入下游就被网关本地规则拦下；</li>
 *     <li>DOWNSTREAM —— 请求已放行并实际调用下游，由下游侧失败。</li>
 * </ul>
 */
public enum RejectReason {

    // ---- 本地错误：请求未进入下游 ----
    MISSING_CALLER("GATEWAY_MISSING_CALLER", 401, ErrorSource.LOCAL),
    UNKNOWN_CALLER_DEFAULT("GATEWAY_UNKNOWN_CALLER_DEFAULT", 200, ErrorSource.LOCAL),
    MISSING_VERSION("GATEWAY_MISSING_VERSION", 400, ErrorSource.LOCAL),
    UNKNOWN_VERSION("GATEWAY_UNKNOWN_VERSION", 400, ErrorSource.LOCAL),
    CALLER_VERSION_NOT_ALLOWED("GATEWAY_CALLER_VERSION_NOT_ALLOWED", 400, ErrorSource.LOCAL),
    ROUTE_NOT_FOUND("GATEWAY_ROUTE_NOT_FOUND", 404, ErrorSource.LOCAL),
    METHOD_NOT_ALLOWED("GATEWAY_METHOD_NOT_ALLOWED", 405, ErrorSource.LOCAL),
    INTERFACE_PAUSED("GATEWAY_INTERFACE_PAUSED", 503, ErrorSource.LOCAL),
    INTERFACE_NOT_OPEN("GATEWAY_INTERFACE_NOT_OPEN", 404, ErrorSource.LOCAL),
    INTERFACE_GRAY_NOT_ALLOWED("GATEWAY_INTERFACE_GRAY_NOT_ALLOWED", 403, ErrorSource.LOCAL),
    INTERFACE_VERSION_NOT_OPEN("GATEWAY_INTERFACE_VERSION_NOT_OPEN", 400, ErrorSource.LOCAL),
    RATE_LIMITED_QUOTA_EXHAUSTED("GATEWAY_RATE_LIMITED", 429, ErrorSource.LOCAL),
    INTERNAL_ERROR("GATEWAY_INTERNAL_ERROR", 500, ErrorSource.LOCAL),

    // ---- 下游错误：请求已放行并真正发起了下游调用 ----
    DOWNSTREAM_TIMEOUT("GATEWAY_DOWNSTREAM_TIMEOUT", 504, ErrorSource.DOWNSTREAM),
    DOWNSTREAM_RATE_LIMITED("GATEWAY_DOWNSTREAM_RATE_LIMITED", 429, ErrorSource.DOWNSTREAM),
    DOWNSTREAM_ERROR("GATEWAY_DOWNSTREAM_ERROR", 502, ErrorSource.DOWNSTREAM);

    private final String code;
    private final int httpStatus;
    private final ErrorSource source;

    RejectReason(String code, int httpStatus, ErrorSource source) {
        this.code = code;
        this.httpStatus = httpStatus;
        this.source = source;
    }

    public String code() {
        return code;
    }

    public int httpStatus() {
        return httpStatus;
    }

    public ErrorSource source() {
        return source;
    }
}
