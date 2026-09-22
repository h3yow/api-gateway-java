package com.github.h3yow.apigatewayjava.gateway.config;

import java.util.Set;

/**
 * 调用方解析后的生效策略：固定窗口配额 + 该调用方被授权使用的版本集合。
 */
public record CallerPolicy(long quota, long windowMillis, Set<ApiVersion> supportedVersions) {
}
