package com.github.h3yow.apigatewayjava.gateway.group;

import com.github.h3yow.apigatewayjava.gateway.model.ApiGroupPolicy;
import com.github.h3yow.apigatewayjava.gateway.model.ApiVersion;
import com.github.h3yow.apigatewayjava.gateway.model.QuotaPolicy;
import com.github.h3yow.apigatewayjava.gateway.model.Route;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 接口组与路由配置（本地状态）。
 *
 * 语义：
 * <ul>
 *     <li>每条下游路径精确注册为一条 {@link Route}，路由必须归属于一个已声明的接口组；</li>
 *     <li>接口组可以有自己的 {@link QuotaPolicy}（限额+窗口），为 {@code null} 表示
 *         回退到“调用方 × 版本”的既有配额策略（仍有限额，绝不无限放行）；</li>
 *     <li>每个组显式声明 {@code openVersions} 白名单；白名单判定只看本组策略，
 *         不会自动套用调用方默认版本策略，也不会把声明版本替换成系统默认版本；</li>
 *     <li>所有引用关系在构造时校验，非法配置直接让启动失败（fail-fast）。</li>
 * </ul>
 */
public class ApiGroupConfig {

    public static final String DEFAULT_GROUP_ID = "__default_group__";

    private final Map<String, Route> routes;
    private final Map<String, ApiGroupPolicy> groups;
    private final String defaultGroupId;

    public ApiGroupConfig(List<Route> routes, Map<String, ApiGroupPolicy> groups,
                          String defaultGroupId, List<ApiVersion> supportedVersions) {
        Map<String, ApiGroupPolicy> g = new LinkedHashMap<>();
        for (ApiGroupPolicy p : groups.values()) {
            validateGroup(p, supportedVersions);
            g.put(p.groupId(), p);
        }
        if (!g.containsKey(defaultGroupId)) {
            throw new IllegalArgumentException("必须配置默认接口组: " + defaultGroupId);
        }
        Map<String, Route> r = new LinkedHashMap<>();
        for (Route route : routes) {
            if (!g.containsKey(route.groupId())) {
                throw new IllegalArgumentException(
                        "路由 " + route.downstreamPath() + " 引用了未声明的接口组: " + route.groupId());
            }
            if (route.allowedMethods().isEmpty()) {
                throw new IllegalArgumentException(
                        "路由 " + route.downstreamPath() + " 必须声明至少一个允许的 HTTP 方法");
            }
            r.put(route.downstreamPath(), route);
        }
        this.routes = Map.copyOf(r);
        this.groups = Map.copyOf(g);
        this.defaultGroupId = defaultGroupId;
    }

    private void validateGroup(ApiGroupPolicy p, List<ApiVersion> supportedVersions) {
        if (p.groupId() == null || p.groupId().isBlank()) {
            throw new IllegalArgumentException("接口组标识不能为空");
        }
        if (p.status() == null) {
            throw new IllegalArgumentException("接口组 " + p.groupId() + " 必须声明上线状态");
        }
        if (p.openVersions() == null) {
            throw new IllegalArgumentException(
                    "接口组 " + p.groupId() + " 必须显式声明 openVersions（可为空集，但不能为 null）");
        }
        for (ApiVersion v : p.openVersions()) {
            if (!supportedVersions.contains(v)) {
                throw new IllegalArgumentException(
                        "接口组 " + p.groupId() + " 的开放版本不在系统支持集合内: " + v.httpValue());
            }
        }
        if (p.quota() != null && (p.quota().limit() <= 0 || p.quota().windowMillis() <= 0)) {
            throw new IllegalArgumentException(
                    "接口组 " + p.groupId() + " 的配额必须为正数: " + p.quota());
        }
    }

    public ApiGroupPolicy policyFor(String groupId) {
        return groups.get(groupId);
    }

    public boolean hasGroup(String groupId) {
        return groups.containsKey(groupId);
    }

    public Route route(String downstreamPath) {
        return routes.get(downstreamPath);
    }

    public String defaultGroupId() {
        return defaultGroupId;
    }

    public Map<String, ApiGroupPolicy> groups() {
        return groups;
    }

    /**
     * 本地演示/测试默认配置。系统支持 v1.0、v1.1：
     * <ul>
     *     <li>{@code default}：OPEN，两版本皆可，无组配额（回退到调用方×版本配额）——挂 echo；</li>
     *     <li>{@code orders}：OPEN，仅 v1.0，组配额 2/1s —— 用于验证组间配额隔离；</li>
     *     <li>{@code payments}：OPEN，v1.0+v1.1，组配额 4/1s；</li>
     *     <li>{@code reports}：PAUSED —— 任何调用均拒绝；</li>
     *     <li>{@code beta-ai}：CANARY，仅 v1.1，仅 caller-a 在灰度白名单；</li>
     *     <li>{@code ledger-v2}：NOT_OPENED，仅声明 v1.1 —— 尚未开放，任何调用均拒绝。</li>
     * </ul>
     */
    public static ApiGroupConfig defaultLocalConfig(List<ApiVersion> supportedVersions) {
        ApiVersion v10 = new ApiVersion(1, 0);
        ApiVersion v11 = new ApiVersion(1, 1);

        Map<String, ApiGroupPolicy> groups = new LinkedHashMap<>();
        groups.put(DEFAULT_GROUP_ID, new ApiGroupPolicy(
                DEFAULT_GROUP_ID, com.github.h3yow.apigatewayjava.gateway.model.GroupStatus.OPEN,
                List.of(v10, v11), List.of(), null));
        groups.put("orders", new ApiGroupPolicy(
                "orders", com.github.h3yow.apigatewayjava.gateway.model.GroupStatus.OPEN,
                List.of(v10), List.of(), new QuotaPolicy(2, 1_000)));
        groups.put("payments", new ApiGroupPolicy(
                "payments", com.github.h3yow.apigatewayjava.gateway.model.GroupStatus.OPEN,
                List.of(v10, v11), List.of(), new QuotaPolicy(4, 1_000)));
        groups.put("reports", new ApiGroupPolicy(
                "reports", com.github.h3yow.apigatewayjava.gateway.model.GroupStatus.PAUSED,
                List.of(v10, v11), List.of(), null));
        groups.put("beta-ai", new ApiGroupPolicy(
                "beta-ai", com.github.h3yow.apigatewayjava.gateway.model.GroupStatus.CANARY,
                List.of(v11), List.of("caller-a"), null));
        groups.put("ledger-v2", new ApiGroupPolicy(
                "ledger-v2", com.github.h3yow.apigatewayjava.gateway.model.GroupStatus.NOT_OPENED,
                List.of(v11), List.of(), null));

        List<Route> routes = new ArrayList<>();
        routes.add(new Route("/mock/downstream/echo", DEFAULT_GROUP_ID, List.of("POST")));
        routes.add(new Route("/mock/downstream/slow", DEFAULT_GROUP_ID, List.of("POST")));
        routes.add(new Route("/mock/downstream/error", DEFAULT_GROUP_ID, List.of("POST")));
        routes.add(new Route("/mock/downstream/busy", DEFAULT_GROUP_ID, List.of("POST")));
        routes.add(new Route("/mock/downstream/orders/create", "orders", List.of("POST")));
        routes.add(new Route("/mock/downstream/payments/charge", "payments", List.of("POST")));
        routes.add(new Route("/mock/downstream/reports/daily", "reports", List.of("POST")));
        routes.add(new Route("/mock/downstream/ai/chat", "beta-ai", List.of("POST")));
        routes.add(new Route("/mock/downstream/ledger/v2", "ledger-v2", List.of("POST")));

        return new ApiGroupConfig(routes, groups, DEFAULT_GROUP_ID, supportedVersions);
    }
}
