package com.github.h3yow.apigatewayjava.gateway.model;

/**
 * 统一错误结构。所有入口、所有错误类型（缺调用方、版本不兼容、限流、下游超时/异常等）
 * 均输出本结构，字段含义稳定：
 * <ul>
 *     <li>traceId：单次请求的可追踪标识（同时出现在响应头 X-Trace-Id）；</li>
 *     <li>errorCode：机器可判定的稳定错误码；</li>
 *     <li>reason：错误分类枚举名；</li>
 *     <li>retryAfterMillis：仅限流场景给出的建议重试等待毫秒，其余为 0。</li>
 * </ul>
 */
public record ErrorResponse(String traceId,
                            String errorCode,
                            String reason,
                            String caller,
                            String version,
                            String message,
                            long retryAfterMillis) {

    public static ErrorResponse of(String traceId, RejectReason reason, String caller,
                                   ApiVersion version, String message, long retryAfterMillis) {
        return new ErrorResponse(
                traceId,
                reason.code(),
                reason.name(),
                caller,
                version == null ? null : version.httpValue(),
                message,
                retryAfterMillis);
    }
}
