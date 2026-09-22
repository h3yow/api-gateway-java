package com.github.h3yow.apigatewayjava.gateway.version;

import com.github.h3yow.apigatewayjava.gateway.model.ApiVersion;
import com.github.h3yow.apigatewayjava.gateway.model.VersionNegotiation;
import com.github.h3yow.apigatewayjava.gateway.model.VersionPolicy;
import com.github.h3yow.apigatewayjava.gateway.model.RejectReason;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 版本协商器。
 *
 * 规则（全程显式判定，绝不静默回落默认版本）：
 * <ol>
 *     <li>请求未声明版本 → MISSING_VERSION 拒绝；</li>
 *     <li>声明版本无法解析 → 由入口层拒绝（UNKNOWN_VERSION）；</li>
 *     <li>声明版本不在“系统受支持版本”集合 → UNKNOWN_VERSION 拒绝；</li>
 *     <li>调用方有显式策略但不包含该版本 → CALLER_VERSION_NOT_ALLOWED 拒绝；</li>
 *     <li>调用方无显式策略 → 落到默认策略（DEFAULT_CALLER），仍按白名单判定；</li>
 *     <li>命中白名单 → 放行，resolved 版本就是声明版本本身（不做小版本替换）。</li>
 * </ol>
 * 受支持版本集合与策略表在构造时确定，判定为纯函数，结论稳定、可重复。
 */
public class VersionNegotiator {

    public static final String DEFAULT_CALLER = "__default__";

    private final List<ApiVersion> supportedVersions;
    private final Map<String, VersionPolicy> policies;

    public VersionNegotiator(List<ApiVersion> supportedVersions, Map<String, VersionPolicy> callerPolicies) {
        this.supportedVersions = List.copyOf(supportedVersions);
        Map<String, VersionPolicy> merged = new LinkedHashMap<>();
        for (Map.Entry<String, VersionPolicy> e : callerPolicies.entrySet()) {
            merged.put(e.getKey(), validate(e.getValue()));
        }
        if (!merged.containsKey(DEFAULT_CALLER)) {
            throw new IllegalArgumentException("必须配置默认版本策略: " + DEFAULT_CALLER);
        }
        this.policies = Map.copyOf(merged);
    }

    private VersionPolicy validate(VersionPolicy policy) {
        for (ApiVersion v : policy.allowedVersions()) {
            if (!supportedVersions.contains(v)) {
                throw new IllegalArgumentException(
                        "调用方 " + policy.caller() + " 的允许版本不在系统支持集合内: " + v.httpValue());
            }
        }
        return policy;
    }

    public VersionNegotiation negotiate(String caller, ApiVersion declared) {
        VersionPolicy policy = policies.getOrDefault(caller, policies.get(DEFAULT_CALLER));
        boolean registered = policies.containsKey(caller);

        if (declared == null) {
            return new VersionNegotiation(caller, null, null, false,
                    "调用方=" + caller + " 未声明 X-Api-Version；按规则拒绝，不回落默认版本",
                    RejectReason.MISSING_VERSION);
        }

        if (!supportedVersions.contains(declared)) {
            return new VersionNegotiation(caller, declared, null, false,
                    "调用方=" + caller + " 声明版本 " + declared.httpValue()
                            + " 不在系统支持集合 " + supportedVersions
                            + "；无法识别，拒绝且不回落",
                    RejectReason.UNKNOWN_VERSION);
        }

        if (!policy.allowedVersions().contains(declared)) {
            String scope = registered ? "调用方显式策略" : "默认策略(调用方未登记)";
            return new VersionNegotiation(caller, declared, null, false,
                    "调用方=" + caller + " 命中" + scope + "，允许版本=" + policy.allowedVersions()
                            + "，声明版本 " + declared.httpValue() + " 与接口不兼容，拒绝且不回落",
                    RejectReason.CALLER_VERSION_NOT_ALLOWED);
        }

        String scope = registered ? "调用方显式策略" : "默认策略(调用方未登记)";
        return new VersionNegotiation(caller, declared, declared, true,
                "调用方=" + caller + " 命中" + scope + "，声明版本 " + declared.httpValue()
                        + " 在允许集合 " + policy.allowedVersions() + " 内，版本协商通过",
                null);
    }

    public List<ApiVersion> supportedVersions() {
        return supportedVersions;
    }

    /**
     * 本地演示/测试默认版本策略：
     * 系统支持 v1.0、v1.1；caller-a 两者皆可，caller-b 仅 v1.0；
     * 未登记调用方默认仅允许 v1.0（明确白名单，而非任意放行）。
     */
    public static VersionNegotiator defaultLocalNegotiator() {
        ApiVersion v10 = new ApiVersion(1, 0);
        ApiVersion v11 = new ApiVersion(1, 1);
        Map<String, VersionPolicy> policies = new LinkedHashMap<>();
        policies.put("caller-a", new VersionPolicy("caller-a", List.of(v10, v11), false));
        policies.put("caller-b", new VersionPolicy("caller-b", List.of(v10), false));
        policies.put(DEFAULT_CALLER, new VersionPolicy(DEFAULT_CALLER, List.of(v10), false));
        return new VersionNegotiator(List.of(v10, v11), policies);
    }
}
