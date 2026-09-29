package com.github.h3yow.apigatewayjava.gateway.group;

import com.github.h3yow.apigatewayjava.gateway.model.ApiGroupPolicy;
import com.github.h3yow.apigatewayjava.gateway.model.ApiVersion;
import com.github.h3yow.apigatewayjava.gateway.model.ErrorSource;
import com.github.h3yow.apigatewayjava.gateway.model.GroupAuthorization;
import com.github.h3yow.apigatewayjava.gateway.model.GroupStatus;
import com.github.h3yow.apigatewayjava.gateway.model.QuotaPolicy;
import com.github.h3yow.apigatewayjava.gateway.model.RejectReason;
import com.github.h3yow.apigatewayjava.gateway.model.Route;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 接口组准入与路由解析单测：
 * OPEN/CANARY/PAUSED/NOT_OPENED 结论可区分，组开放版本互不影响，
 * 不静默替换版本；404/405 均归类为本地错误；非法配置 fail-fast。
 */
class GroupPolicyTest {

    private static final ApiVersion V10 = new ApiVersion(1, 0);
    private static final ApiVersion V11 = new ApiVersion(1, 1);

    private final GroupAuthorizer authorizer = new GroupAuthorizer();

    private static void log(GroupAuthorization a) {
        System.out.printf("[GroupPolicyTest] group=%s allowed=%s reason=%s basis=%s%n",
                a.groupId(), a.allowed(), a.rejectReason(), a.basis());
    }

    @Test
    void pausedAndNotOpenedAreAlwaysRejected_withDistinctReasons() {
        ApiGroupPolicy paused = new ApiGroupPolicy("reports", GroupStatus.PAUSED,
                List.of(V10, V11), List.of(), null);
        ApiGroupPolicy notOpened = new ApiGroupPolicy("ledger-v2", GroupStatus.NOT_OPENED,
                List.of(V11), List.of(), null);

        GroupAuthorization p = authorizer.authorize("caller-a", V10, paused);
        GroupAuthorization n = authorizer.authorize("caller-a", V11, notOpened);
        log(p);
        log(n);
        assertFalse(p.allowed());
        assertFalse(n.allowed());
        assertEquals(RejectReason.GROUP_PAUSED, p.rejectReason());
        assertEquals(503, p.rejectReason().httpStatus());
        assertEquals(RejectReason.GROUP_NOT_OPENED, n.rejectReason());
        assertEquals(404, n.rejectReason().httpStatus());
        assertSame(ErrorSource.LOCAL, p.rejectReason().source());
        assertSame(ErrorSource.LOCAL, n.rejectReason().source());
    }

    @Test
    void canaryOnlyAllowsWhitelistedCallers() {
        ApiGroupPolicy canary = new ApiGroupPolicy("beta-ai", GroupStatus.CANARY,
                List.of(V11), List.of("caller-a"), null);

        GroupAuthorization allowed = authorizer.authorize("caller-a", V11, canary);
        GroupAuthorization denied = authorizer.authorize("caller-b", V11, canary);
        log(allowed);
        log(denied);
        assertTrue(allowed.allowed());
        assertFalse(denied.allowed());
        assertEquals(RejectReason.GROUP_CANARY_NOT_ALLOWED, denied.rejectReason());
        assertEquals(403, denied.rejectReason().httpStatus());
    }

    @Test
    void groupVersionWhitelistIsIndependentAndNoSilentSwap() {
        ApiGroupPolicy orders = new ApiGroupPolicy("orders", GroupStatus.OPEN,
                List.of(V10), List.of(), new QuotaPolicy(2, 1_000));
        ApiGroupPolicy payments = new ApiGroupPolicy("payments", GroupStatus.OPEN,
                List.of(V10, V11), List.of(), new QuotaPolicy(4, 1_000));

        // orders 只开 v1.0：caller-a 即使在调用方级别能用 v1.1，组级别也必须拒绝，不替换为 v1.0
        GroupAuthorization bad = authorizer.authorize("caller-a", V11, orders);
        log(bad);
        assertFalse(bad.allowed());
        assertEquals(RejectReason.GROUP_VERSION_NOT_ALLOWED, bad.rejectReason());
        assertTrue(bad.basis().contains("不静默替换"));

        // 同一版本在 payments 开放：组间规则互不影响
        assertTrue(authorizer.authorize("caller-a", V11, payments).allowed());
        // orders 的 v1.0 正常
        assertTrue(authorizer.authorize("caller-b", V10, orders).allowed());

        // 稳定性：同输入连续 50 次一致
        for (int i = 0; i < 50; i++) {
            GroupAuthorization a = authorizer.authorize("caller-a", V11, orders);
            assertFalse(a.allowed());
            assertEquals(RejectReason.GROUP_VERSION_NOT_ALLOWED, a.rejectReason());
        }
    }

    @Test
    void routeResolutionDistinguishesNotFoundAndMethodNotAllowed_bothLocal() {
        ApiGroupConfig config = ApiGroupConfig.defaultLocalConfig(List.of(V10, V11));
        RouteRegistry registry = new RouteRegistry(config);

        RouteRegistry.Resolution ok = registry.resolve("POST", "/mock/downstream/orders/create");
        RouteRegistry.Resolution notFound = registry.resolve("POST", "/mock/does/not/exist");
        RouteRegistry.Resolution method = registry.resolve("GET", "/mock/downstream/orders/create");

        System.out.printf("[GroupPolicyTest] route ok=%s 404=%s 405=%s%n",
                ok.basis(), notFound.rejectReason(), method.rejectReason());

        assertTrue(ok.matched());
        assertEquals("orders", ok.route().groupId());
        assertNull(ok.rejectReason());

        assertFalse(notFound.matched());
        assertNull(notFound.route());
        assertEquals(RejectReason.ROUTE_NOT_FOUND, notFound.rejectReason());
        assertEquals(404, notFound.rejectReason().httpStatus());

        assertFalse(method.matched());
        assertEquals(RejectReason.METHOD_NOT_ALLOWED, method.rejectReason());
        assertEquals(405, method.rejectReason().httpStatus());
        assertEquals("orders", method.route().groupId(), "405 时路径确实存在，仅方法不允许");

        assertSame(ErrorSource.LOCAL, notFound.rejectReason().source());
        assertSame(ErrorSource.LOCAL, method.rejectReason().source());
    }

    @Test
    void invalidConfigFailsFast() {
        // 路由引用未声明的组
        assertThrows(IllegalArgumentException.class, () -> new ApiGroupConfig(
                List.of(new Route("/x", "ghost", List.of("POST"))),
                Map.of(ApiGroupConfig.DEFAULT_GROUP_ID,
                        new ApiGroupPolicy(ApiGroupConfig.DEFAULT_GROUP_ID, GroupStatus.OPEN,
                                List.of(V10), List.of(), null)),
                ApiGroupConfig.DEFAULT_GROUP_ID, List.of(V10)));

        // 开放版本不在系统支持集合
        assertThrows(IllegalArgumentException.class, () -> new ApiGroupConfig(
                List.of(),
                Map.of("g", new ApiGroupPolicy("g", GroupStatus.OPEN, List.of(V11), List.of(), null)),
                "g", List.of(V10)));

        // 缺少默认组
        assertThrows(IllegalArgumentException.class, () -> new ApiGroupConfig(
                List.of(),
                Map.of("g", new ApiGroupPolicy("g", GroupStatus.OPEN, List.of(V10), List.of(), null)),
                ApiGroupConfig.DEFAULT_GROUP_ID, List.of(V10)));
    }
}
