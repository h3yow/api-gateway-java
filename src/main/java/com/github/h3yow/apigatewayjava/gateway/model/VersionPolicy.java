package com.github.h3yow.apigatewayjava.gateway.model;

import java.util.List;

/**
 * 调用方版本策略：该调用方被允许的版本组合（版本集合判定保持稳定，按声明顺序匹配）。
 */
public record VersionPolicy(String caller, List<ApiVersion> allowedVersions, boolean useDefault) {
}
