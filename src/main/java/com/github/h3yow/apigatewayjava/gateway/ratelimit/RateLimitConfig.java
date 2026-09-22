package com.github.h3yow.apigatewayjava.gateway.ratelimit;

import com.github.h3yow.apigatewayjava.gateway.model.ApiVersion;
import com.github.h3yow.apigatewayjava.gateway.model.QuotaPolicy;

import java.util.HashMap;
import java.util.Map;

/**
 * 调用方配额配置（本地状态）。
 *
 * 维度：调用方（X-Caller-Id） × 接口版本（X-Api-Version），二者共同决定一条配额，
 * 同一调用方不同版本的配额互相独立。
 *
 * 查找键：{@code caller + "|" + version.httpValue()}。
 * 回退顺序（命中即止，保证未登记调用方也不会无限放行）：
 * <ol>
 *     <li>caller|version 精确配额；</li>
 *     <li>caller|*     该调用方的版本无关默认配额；</li>
 *     <li>__default__|version 该版本的全局默认配额；</li>
 *     <li>__default__|*       兜底默认配额（始终存在）。</li>
 * </ol>
 */
public class RateLimitConfig {

    public static final String DEFAULT_CALLER = "__default__";
    public static final String ANY_VERSION = "*";

    private final Map<String, QuotaPolicy> quotas;
    private final QuotaPolicy fallback;

    public RateLimitConfig(Map<String, QuotaPolicy> quotas) {
        this.quotas = new HashMap<>(quotas);
        QuotaPolicy fb = this.quotas.get(DEFAULT_CALLER + "|" + ANY_VERSION);
        if (fb == null) {
            throw new IllegalArgumentException("必须配置兜底配额: " + DEFAULT_CALLER + "|" + ANY_VERSION);
        }
        this.fallback = fb;
    }

    public static String key(String caller, String versionKey) {
        return caller + "|" + versionKey;
    }

    public QuotaPolicy policyFor(String caller, ApiVersion version) {
        String v = version.httpValue();
        QuotaPolicy exact = quotas.get(key(caller, v));
        if (exact != null) {
            return exact;
        }
        QuotaPolicy callerDefault = quotas.get(key(caller, ANY_VERSION));
        if (callerDefault != null) {
            return callerDefault;
        }
        QuotaPolicy versionDefault = quotas.get(key(DEFAULT_CALLER, v));
        if (versionDefault != null) {
            return versionDefault;
        }
        return fallback;
    }

    /**
     * 说明一条配额最终来自哪一级配置，供日志解释判定依据。
     */
    public String basisOf(String caller, ApiVersion version) {
        String v = version.httpValue();
        if (quotas.containsKey(key(caller, v))) {
            return "exact quota for " + key(caller, v);
        }
        if (quotas.containsKey(key(caller, ANY_VERSION))) {
            return "caller-default quota for " + key(caller, ANY_VERSION);
        }
        if (quotas.containsKey(key(DEFAULT_CALLER, v))) {
            return "version-default quota for " + key(DEFAULT_CALLER, v);
        }
        return "fallback default quota for " + key(DEFAULT_CALLER, ANY_VERSION);
    }

    public Map<String, QuotaPolicy> quotas() {
        return Map.copyOf(quotas);
    }

    /**
     * 本地演示/测试用默认配额表：
     * caller-a：v1.0=5/1s、v1.1=10/1s；caller-b：任意版本 3/1s；
     * 未登记调用方：1/1s（明确但保守的默认策略，绝不无限放行）。
     */
    public static RateLimitConfig defaultLocalConfig() {
        Map<String, QuotaPolicy> map = new HashMap<>();
        map.put(key("caller-a", "v1.0"), new QuotaPolicy(5, 1_000));
        map.put(key("caller-a", "v1.1"), new QuotaPolicy(10, 1_000));
        map.put(key("caller-b", ANY_VERSION), new QuotaPolicy(3, 1_000));
        map.put(key(DEFAULT_CALLER, ANY_VERSION), new QuotaPolicy(1, 1_000));
        return new RateLimitConfig(map);
    }
}
