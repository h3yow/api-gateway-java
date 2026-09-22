package com.github.h3yow.apigatewayjava.gateway.downstream;

/**
 * 下游调用结果。body 为下游返回文本；downstreamStatus 为下游 HTTP 状态码。
 */
public record DownstreamResponse(int status, String body) {
}
