package com.github.h3yow.apigatewayjava.gateway.model;

/**
 * 单维度配额策略：每个固定窗口允许的请求数与窗口长度（毫秒）。
 */
public record QuotaPolicy(long limit, long windowMillis) {
}
