package com.github.h3yow.apigatewayjava.gateway.model;

import java.util.List;

/**
 * 接口组策略（骨架定义，逻辑随后逐步填充）。
 *
 * @param groupId         接口组标识
 * @param status          上线状态
 * @param openVersions    该组开放的版本白名单（显式声明；不开放任何版本时为空集，绝不默认全放开）
 * @param canaryCallers   CANARY 状态下允许访问的调用方白名单
 * @param quota           该组自己的限额与窗口；为 null 表示回退到“调用方×版本”既有配额策略
 */
public record ApiGroupPolicy(String groupId,
                             GroupStatus status,
                             List<ApiVersion> openVersions,
                             List<String> canaryCallers,
                             QuotaPolicy quota) {
}
