package com.github.h3yow.apigatewayjava.gateway.ratelimit;

import com.github.h3yow.apigatewayjava.gateway.config.ApiVersion;

/**
 * 配额维度：调用方 + 接口版本。同一调用方不同版本互不影响。
 */
public record QuotaKey(String caller, ApiVersion version) {
}
