package com.github.h3yow.apigatewayjava.gateway.routing;

import com.github.h3yow.apigatewayjava.gateway.model.ApiVersion;
import com.github.h3yow.apigatewayjava.gateway.model.InterfaceGroupConfig;
import com.github.h3yow.apigatewayjava.gateway.model.QuotaPolicy;
import com.github.h3yow.apigatewayjava.gateway.model.QuotaResolution;
import com.github.h3yow.apigatewayjava.gateway.ratelimit.RateLimitConfig;

/**
 * 配额解析。维度为 {@code 调用方 × 接口组 × 版本}：
 * <ol>
 *     <li>接口组显式配置了自有配额（限额+窗口）→ 使用接口组配额，计数与其他接口组完全隔离；</li>
 *     <li>否则<b>回退</b>到现有“调用方×版本”配额（RateLimitConfig 的四级命中：
 *         caller|version → caller|* → __default__|version → __default__|*）。
 *         回退的含义是“借用调用方×版本策略的限额值”，但计数维度仍带接口组，
 *         不同接口组之间互不占用额度；且最终必有兜底默认配额，未配置接口组绝不等于无限放行。</li>
 * </ol>
 */
public class QuotaResolver {

    /** 未命中任何接口组时使用的内部组标识（仅用于回退维度的键，保持计数仍按组隔离）。 */
    public static final String UNGROUPED = "__ungrouped__";

    private final RateLimitConfig rateLimitConfig;

    public QuotaResolver(RateLimitConfig rateLimitConfig) {
        this.rateLimitConfig = rateLimitConfig;
    }

    /**
     * 按接口组配置解析配额。group 为 null（未命中组）时等价于纯调用方×版本回退。
     */
    public QuotaResolution resolve(String caller, InterfaceGroupConfig group, ApiVersion version) {
        String groupId = group == null ? UNGROUPED : group.id();
        if (group != null && group.quota() != null) {
            QuotaPolicy q = group.quota();
            return new QuotaResolution(caller, groupId, version, q, true,
                    "interface-group quota for group=" + groupId
                            + " limit=" + q.limit() + " windowMillis=" + q.windowMillis()
                            + "; isolated per caller|group|version");
        }
        QuotaPolicy fallback = rateLimitConfig.policyFor(caller, version);
        return new QuotaResolution(caller, groupId, version, fallback, false,
                "group=" + groupId + " has no dedicated quota, fallback to caller×version policy ("
                        + rateLimitConfig.basisOf(caller, version)
                        + "); counter still isolated by group");
    }
}
