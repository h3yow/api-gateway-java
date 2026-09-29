package com.github.h3yow.apigatewayjava.gateway.model;

import java.util.List;
import java.util.Set;

/**
 * 接口组策略。
 *
 * @param id              接口组标识（稳定、可解释）
 * @param status          上线状态
 * @param openVersions    该接口组开放的版本白名单（显式声明，绝不隐式套用默认接口策略）
 * @param grayCallers     仅当 status=GRAY 时生效的灰度调用方白名单
 * @param allowedMethods  该接口组允许的 HTTP 方法；null 表示按默认（POST）
 * @param quota           接口组自有配额；null 表示回退到 调用方×版本 配额（回退绝不是无限放行）
 */
public record InterfaceGroupConfig(String id,
                                   InterfaceStatus status,
                                   List<ApiVersion> openVersions,
                                   Set<String> grayCallers,
                                   Set<String> allowedMethods,
                                   QuotaPolicy quota) {
}
