package com.github.h3yow.apigatewayjava.gateway.model;

/**
 * 统一错误结构。所有入口、所有错误类型（缺调用方、版本不兼容、接口组状态、配额耗尽、
 * 路由不存在、方法不支持、下游超时/异常等）均输出本结构，字段含义稳定：
 * <ul>
 *     <li>traceId：单次请求唯一追踪标识（同时出现在响应头 X-Trace-Id，二者严格一致，每响应仅一个）；</li>
 *     <li>errorCode：机器可判定的稳定错误码；</li>
 *     <li>reason：错误分类枚举名；</li>
 *     <li>source：错误来源，LOCAL=网关本地拦截（未进入下游），DOWNSTREAM=下游侧失败；</li>
 *     <li>group：命中的接口组标识（无接口组上下文时为 null）；</li>
 *     <li>retryAfterMillis：仅本地配额限流场景给出的建议重试等待毫秒，其余为 0。</li>
 * </ul>
 */
public record ErrorResponse(String traceId,
                            String errorCode,
                            String reason,
                            String source,
                            String caller,
                            String version,
                            String group,
                            String message,
                            long retryAfterMillis) {

    public static ErrorResponse of(String traceId, RejectReason reason, String caller,
                                   ApiVersion version, String message, long retryAfterMillis) {
        return of(traceId, reason, caller, version, null, message, retryAfterMillis);
    }

    public static ErrorResponse of(String traceId, RejectReason reason, String caller,
                                   ApiVersion version, String group,
                                   String message, long retryAfterMillis) {
        return new ErrorResponse(
                traceId,
                reason.code(),
                reason.name(),
                reason.source().name(),
                caller,
                version == null ? null : version.httpValue(),
                group,
                message,
                retryAfterMillis);
    }
}
