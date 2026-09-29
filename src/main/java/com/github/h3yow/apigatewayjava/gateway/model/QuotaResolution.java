package com.github.h3yow.apigatewayjava.gateway.model;

/**
 * 一次 {@code 调用方 × 接口组 × 版本} 的配额解析结论。
 *
 * @param caller       调用方
 * @param groupId      接口组标识
 * @param version      版本
 * @param policy       最终生效的配额策略（永不为 null：无接口组配额时回退到调用方×版本策略，
 *                     最终还有兜底默认配额，因此未配置接口组绝不会变成无限放行）
 * @param groupScoped  配额是否来自接口组自有配置（false 表示回退到调用方×版本策略）
 * @param basis        可解释的来源依据
 */
public record QuotaResolution(String caller,
                              String groupId,
                              ApiVersion version,
                              QuotaPolicy policy,
                              boolean groupScoped,
                              String basis) {
}
