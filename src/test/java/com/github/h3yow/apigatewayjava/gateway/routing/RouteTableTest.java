package com.github.h3yow.apigatewayjava.gateway.routing;

import com.github.h3yow.apigatewayjava.gateway.model.ApiVersion;
import com.github.h3yow.apigatewayjava.gateway.model.InterfaceGroupConfig;
import com.github.h3yow.apigatewayjava.gateway.model.InterfaceStatus;
import com.github.h3yow.apigatewayjava.gateway.model.QuotaPolicy;
import com.github.h3yow.apigatewayjava.gateway.model.Route;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 路由表单测：最长前缀匹配、边界不误命中、未命中、启动期 fail-fast 校验。
 */
class RouteTableTest {

    private static final ApiVersion V10 = new ApiVersion(1, 0);
    private static final ApiVersion V11 = new ApiVersion(1, 1);

    private InterfaceGroupConfig group(String id) {
        return new InterfaceGroupConfig(id, InterfaceStatus.ACTIVE, List.of(V10, V11),
                Set.of(), null, null);
    }

    private RouteTable table() {
        Map<String, InterfaceGroupConfig> map = new LinkedHashMap<>();
        map.put("/api/orders", group("orders"));
        map.put("/api/orders/special", group("orders-special"));
        return new RouteTable(List.of(V10, V11), map);
    }

    @Test
    void resolvesExactAndNestedPathsToGroup() {
        RouteTable table = table();
        Route exact = table.resolve("POST", "/api/orders", V10);
        Route nested = table.resolve("POST", "/api/orders/123", V10);
        assertTrue(exact.matched());
        assertEquals("orders", exact.group().id());
        assertTrue(nested.matched());
        assertEquals("orders", nested.group().id());
        assertEquals(V10, nested.declaredVersion(), "路由需原样携带声明版本");
    }

    @Test
    void longestPrefixWins() {
        Route special = table().resolve("POST", "/api/orders/special/9", V11);
        assertTrue(special.matched());
        assertEquals("orders-special", special.group().id());
    }

    @Test
    void prefixMustRespectPathBoundary() {
        // /api/ordersX 不得误命中 /api/orders
        Route wrong = table().resolve("POST", "/api/ordersX", V10);
        assertFalse(wrong.matched());
    }

    @Test
    void unknownPathIsUnmatched_local404Later() {
        Route none = table().resolve("POST", "/nope", V10);
        assertFalse(none.matched());
    }

    @Test
    void rejectsInvalidBuilders() {
        // 开放版本为空 → fail-fast（禁止隐式默认接口策略）
        assertThrows(IllegalArgumentException.class, () -> new RouteTable(List.of(V10, V11),
                Map.of("/a", new InterfaceGroupConfig("a", InterfaceStatus.ACTIVE, List.of(),
                        Set.of(), null, null))));
        // 开放版本超出系统支持集合 → fail-fast
        assertThrows(IllegalArgumentException.class, () -> new RouteTable(List.of(V10),
                Map.of("/a", new InterfaceGroupConfig("a", InterfaceStatus.ACTIVE, List.of(V11),
                        Set.of(), null, null))));
        // GRAY 必须显式给出灰度调用方集合
        assertThrows(IllegalArgumentException.class, () -> new RouteTable(List.of(V10),
                Map.of("/a", new InterfaceGroupConfig("a", InterfaceStatus.GRAY, List.of(V10),
                        null, null, null))));
        // 非法配额
        assertThrows(IllegalArgumentException.class, () -> new RouteTable(List.of(V10),
                Map.of("/a", new InterfaceGroupConfig("a", InterfaceStatus.ACTIVE, List.of(V10),
                        Set.of(), null, new QuotaPolicy(0, 1_000)))));
        // 非法前缀
        assertThrows(IllegalArgumentException.class, () -> new RouteTable(List.of(V10),
                Map.of("noslash", group("x"))));
    }
}
