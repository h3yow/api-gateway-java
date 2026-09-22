package com.github.h3yow.apigatewayjava.gateway.model;

/**
 * 网关正常转发后的响应。
 */
public record GatewayResponse(int status, String body) {
}
