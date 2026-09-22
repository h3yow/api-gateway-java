package com.github.h3yow.apigatewayjava.gateway.error;

/**
 * 统一对外错误结构。traceId 贯穿网关与下游调用以便追踪。
 */
public record ApiError(String errorCode,
                       String message,
                       String caller,
                       String version,
                       String reason,
                       Long retryAfterMillis,
                       String traceId) {
}
