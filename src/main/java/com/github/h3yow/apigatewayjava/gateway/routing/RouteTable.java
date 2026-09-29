package com.github.h3yow.apigatewayjava.gateway.routing;

import com.github.h3yow.apigatewayjava.gateway.model.ApiVersion;
import com.github.h3yow.apigatewayjava.gateway.model.InterfaceGroupConfig;
import com.github.h3yow.apigatewayjava.gateway.model.InterfaceStatus;
import com.github.h3yow.apigatewayjava.gateway.model.QuotaPolicy;
import com.github.h3yow.apigatewayjava.gateway.model.Route;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 路由表：把下游路径归入一个“接口组”。
 *
 * <p>匹配规则：按配置前缀做最长前缀匹配；路径等于前缀，或路径以前缀 + "/" 开头即视为命中。
 * 未命中任何前缀时返回 {@code matched=false}，由编排层按本地错误 ROUTE_NOT_FOUND 拒绝
 * （绝不转发、绝不伪装成下游故障）。
 *
 * <p>启动期强校验（错误配置直接 fail-fast）：
 * <ul>
 *     <li>接口组 id/前缀非空白，前缀必须以 "/" 开头；</li>
 *     <li>开放版本列表非空，且必须是系统受支持版本集合的子集；</li>
 *     <li>状态非空；GRAY 状态必须显式给出灰度调用方集合（可为空集，表示无人灰度，但必须显式）；</li>
 *     <li>自有配额若配置，限额与窗口都必须为正。</li>
 * </ul>
 */
public class RouteTable {

    private final List<ApiVersion> supportedVersions;
    private final Map<String, InterfaceGroupConfig> groupsByPrefix;

    public RouteTable(List<ApiVersion> supportedVersions, Map<String, InterfaceGroupConfig> groupsByPrefix) {
        this.supportedVersions = List.copyOf(supportedVersions);
        Map<String, InterfaceGroupConfig> validated = new LinkedHashMap<>();
        for (Map.Entry<String, InterfaceGroupConfig> e : groupsByPrefix.entrySet()) {
            String prefix = normalizePrefix(e.getKey());
            validated.put(prefix, validate(prefix, e.getValue()));
        }
        this.groupsByPrefix = Map.copyOf(validated);
    }

    private static String normalizePrefix(String prefix) {
        if (prefix == null || prefix.isBlank()) {
            throw new IllegalArgumentException("接口组前缀不能为空");
        }
        String p = prefix.trim();
        if (!p.startsWith("/")) {
            throw new IllegalArgumentException("接口组前缀必须以 / 开头: " + prefix);
        }
        while (p.length() > 1 && p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        return p;
    }

    private InterfaceGroupConfig validate(String prefix, InterfaceGroupConfig g) {
        if (g == null) {
            throw new IllegalArgumentException("接口组配置不能为空: " + prefix);
        }
        if (g.id() == null || g.id().isBlank()) {
            throw new IllegalArgumentException("接口组 id 不能为空: " + prefix);
        }
        if (g.status() == null) {
            throw new IllegalArgumentException("接口组状态不能为空: " + g.id());
        }
        if (g.openVersions() == null || g.openVersions().isEmpty()) {
            throw new IllegalArgumentException(
                    "接口组 " + g.id() + " 必须显式声明开放版本（不允许空开放列表隐式套用默认策略）");
        }
        for (ApiVersion v : g.openVersions()) {
            if (!supportedVersions.contains(v)) {
                throw new IllegalArgumentException(
                        "接口组 " + g.id() + " 的开放版本 " + v.httpValue() + " 不在系统支持集合内");
            }
        }
        if (g.status() == InterfaceStatus.GRAY && g.grayCallers() == null) {
            throw new IllegalArgumentException("接口组 " + g.id() + " 处于 GRAY 状态时必须显式给出灰度调用方集合");
        }
        if (g.quota() != null) {
            QuotaPolicy q = g.quota();
            if (q.limit() <= 0 || q.windowMillis() <= 0) {
                throw new IllegalArgumentException("接口组 " + g.id() + " 的配额限额与窗口必须为正数");
            }
        }
        return g;
    }

    /**
     * 解析路由。最长前缀命中；未命中返回 matched=false。
     */
    public Route resolve(String method, String path, ApiVersion declaredVersion) {
        String bestPrefix = null;
        for (String prefix : groupsByPrefix.keySet()) {
            if (matches(path, prefix) && (bestPrefix == null || prefix.length() > bestPrefix.length())) {
                bestPrefix = prefix;
            }
        }
        if (bestPrefix == null) {
            return new Route(path, method, declaredVersion, false, null);
        }
        return new Route(path, method, declaredVersion, true, groupsByPrefix.get(bestPrefix));
    }

    private static boolean matches(String path, String prefix) {
        if (path == null || path.length() < prefix.length()) {
            return false;
        }
        if (!path.startsWith(prefix)) {
            return false;
        }
        if (path.length() == prefix.length()) {
            return true;
        }
        // 前缀必须在路径分隔符边界上，避免 /api/orderX 误命中 /api/orders
        return path.charAt(prefix.length()) == '/';
    }

    public Map<String, InterfaceGroupConfig> groupsByPrefix() {
        return groupsByPrefix;
    }

    /**
     * 本地演示/测试默认路由表：
     * <ul>
     *     <li>mock-downstream：保留既有 mock 下游，全版本开放，配额回退到调用方×版本策略；</li>
     *     <li>orders：全量开放 v1.0/v1.1，接口组自有配额 2/1s（与其他组隔离）；</li>
     *     <li>reports：全量开放 v1.0/v1.1，无自有配额，回退调用方×版本策略；</li>
     *     <li>beta：灰度中，仅 v1.1 且仅 caller-a 在灰度白名单，自有配额 2/1s；</li>
     *     <li>legacy：已暂停（v1.0），任何调用方都拒绝；</li>
     *     <li>future：尚未开放（v1.1），任何调用方都拒绝。</li>
     * </ul>
     */
    public static RouteTable defaultLocalTable(List<ApiVersion> supported) {
        Map<String, InterfaceGroupConfig> map = new LinkedHashMap<>();
        map.put("/mock/downstream", new InterfaceGroupConfig(
                "mock-downstream", InterfaceStatus.ACTIVE, List.of(supported.get(0), supported.get(1)),
                Set.of(), null, null));
        map.put("/api/orders", new InterfaceGroupConfig(
                "orders", InterfaceStatus.ACTIVE, List.of(supported.get(0), supported.get(1)),
                Set.of(), null, new QuotaPolicy(2, 1_000)));
        map.put("/api/reports", new InterfaceGroupConfig(
                "reports", InterfaceStatus.ACTIVE, List.of(supported.get(0), supported.get(1)),
                Set.of(), null, null));
        map.put("/api/beta", new InterfaceGroupConfig(
                "beta", InterfaceStatus.GRAY, List.of(supported.get(1)),
                Set.of("caller-a"), null, new QuotaPolicy(2, 1_000)));
        map.put("/api/legacy", new InterfaceGroupConfig(
                "legacy", InterfaceStatus.PAUSED, List.of(supported.get(0)),
                Set.of(), null, null));
        map.put("/api/future", new InterfaceGroupConfig(
                "future", InterfaceStatus.NOT_OPEN, List.of(supported.get(1)),
                Set.of(), null, null));
        return new RouteTable(supported, map);
    }
}
