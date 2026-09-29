package com.github.h3yow.apigatewayjava.gateway.model;

/**
 * 统一错误结构。所有入口、所有错误类型（缺调用方、版本不兼容、路由不存在、接口组暂停/未开放、
 * 限流、下游超时/异常等）均输出本结构，字段含义稳定：
 * <ul>
 *     <li>traceId：单次请求唯一追踪标识（同时出现在响应头 X-Trace-Id，二者一致，全响应仅这一个 ID）；</li>
 *     <li>errorSource：错误来源分类（LOCAL=请求未进下游即被网关本地拦下；DOWNSTREAM=下游自身失败）；</li>
 *     <li>errorCode：机器可判定的稳定错误码；</li>
 *     <li>reason：错误分类枚举名；</li>
 *     <li>group：命中的接口组（若已解析出）；</li>
 *     <li>retryAfterMillis：仅限流场景给出的建议重试等待毫秒，其余为 0。</li>
 * </ul>
 */
public record ErrorResponse(String traceId,
                            String errorSource,
                            String errorCode,
                            String reason,
                            String caller,
                            String version,
                            String group,
                            String message,
                            long retryAfterMillis) {

    public static ErrorResponse of(String traceId, RejectReason reason, String caller,
                                   ApiVersion version, String group,
                                   String message, long retryAfterMillis) {
        return new ErrorResponse(
                traceId,
                reason.source().name(),
                reason.code(),
                reason.name(),
                caller,
                version == null ? null : version.httpValue(),
                group,
                message,
                retryAfterMillis);
    }
}
