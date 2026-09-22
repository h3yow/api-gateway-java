package com.github.h3yow.apigatewayjava.gateway.model;

/**
 * 进入网关的规范化请求。
 */
public record GatewayRequest(String traceId,
                             String caller,
                             ApiVersion declaredVersion,
                             String method,
                             String path,
                             String body) {
}
