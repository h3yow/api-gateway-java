package com.github.h3yow.apigatewayjava.gateway.group;

import com.github.h3yow.apigatewayjava.gateway.model.ApiGroupPolicy;
import com.github.h3yow.apigatewayjava.gateway.model.ApiVersion;
import com.github.h3yow.apigatewayjava.gateway.model.QuotaPolicy;
import com.github.h3yow.apigatewayjava.gateway.ratelimit.RateLimitConfig;

import java.util.Map;

/**
 * “调用方 × 版本 × 接口组”配额解析。
 *
 * 同一调用方同一版本在不同接口组之间各自独立计数、互不占用额度。每组解析出的最终配额
 * 按以下顺序命中即止（任何一条路径最终都落到有限配额，未配置组绝不会变成无限放行）：
 * <ol>
 *     <li>组内精确配额：{@code caller|version|groupId}（可选的组内调用方级覆盖）；</li>
 *     <li>组级配额：接口组自身配置的 {@link QuotaPolicy}（自己的限额与窗口）；</li>
 *     <li>回退到既有“调用方 × 版本”配额策略（exact → caller|* → default|version → default|*）。</li>
 * </ol>
 * 因此“接口组没单独配配额”等价于回退，而不是放开。
 */
public class GroupQuotaResolver implements QuotaResolver {

    private final ApiGroupConfig groupConfig;
    private final RateLimitConfig callerConfig;
    private final Map<String, QuotaPolicy> groupOverrides;

    /**
     * @param groupOverrides 键格式 {@code caller|version|groupId}，用于在组级配额之上做
     *                       调用方×版本覆盖；可为空
     */
    public GroupQuotaResolver(ApiGroupConfig groupConfig,
                              RateLimitConfig callerConfig,
                              Map<String, QuotaPolicy> groupOverrides) {
        this.groupConfig = groupConfig;
        this.callerConfig = callerConfig;
        this.groupOverrides = groupOverrides == null ? Map.of() : Map.copyOf(groupOverrides);
    }

    public static String overrideKey(String caller, String versionKey, String groupId) {
        return caller + "|" + versionKey + "|" + groupId;
    }

    @Override
    public Resolved resolve(String caller, ApiVersion version, String groupId) {
        String v = version.httpValue();

        QuotaPolicy exact = groupOverrides.get(overrideKey(caller, v, groupId));
        if (exact != null) {
            return new Resolved(exact,
                    "group exact quota for " + overrideKey(caller, v, groupId)
                            + " (接口组内调用方×版本覆盖)");
        }
        QuotaPolicy callerAny = groupOverrides.get(
                overrideKey(caller, RateLimitConfig.ANY_VERSION, groupId));
        if (callerAny != null) {
            return new Resolved(callerAny,
                    "group caller-default quota for "
                            + overrideKey(caller, RateLimitConfig.ANY_VERSION, groupId));
        }

        ApiGroupPolicy policy = groupConfig.policyFor(groupId);
        if (policy != null && policy.quota() != null) {
            return new Resolved(policy.quota(),
                    "group quota for group=" + groupId + " limit=" + policy.quota().limit()
                            + " windowMillis=" + policy.quota().windowMillis()
                            + " (接口组独立限额与窗口)");
        }

        QuotaPolicy fallback = callerConfig.policyFor(caller, version);
        return new Resolved(fallback,
                "group " + groupId + " 无独立配额，回退到调用方×版本策略: "
                        + callerConfig.basisOf(caller, version));
    }
}
