package com.github.h3yow.apigatewayjava.gateway.routing;

import com.github.h3yow.apigatewayjava.gateway.model.ApiVersion;
import com.github.h3yow.apigatewayjava.gateway.model.InterfaceGroupConfig;
import com.github.h3yow.apigatewayjava.gateway.model.InterfaceStatus;
import com.github.h3yow.apigatewayjava.gateway.model.QuotaPolicy;
import com.github.h3yow.apigatewayjava.gateway.model.QuotaResolution;
import com.github.h3yow.apigatewayjava.gateway.ratelimit.RateLimitConfig;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 配额解析单测：接口组自有配额优先；无组配额时回退调用方×版本策略；
 * 未登记调用方 + 未配置组配额时仍落到兜底默认配额（绝不无限放行）。
 */
class QuotaResolverTest {

    private static final ApiVersion V10 = new ApiVersion(1, 0);
    private static final ApiVersion V11 = new ApiVersion(1, 1);

    private RateLimitConfig rateConfig() {
        Map<String, QuotaPolicy> map = new HashMap<>();
        map.put(RateLimitConfig.key("caller-a", "v1.0"), new QuotaPolicy(5, 1_000));
        map.put(RateLimitConfig.key("caller-b", RateLimitConfig.ANY_VERSION), new QuotaPolicy(3, 2_000));
        map.put(RateLimitConfig.key(RateLimitConfig.DEFAULT_CALLER, RateLimitConfig.ANY_VERSION),
                new QuotaPolicy(1, 1_000));
        return new RateLimitConfig(map);
    }

    private InterfaceGroupConfig group(String id, QuotaPolicy quota) {
        return new InterfaceGroupConfig(id, InterfaceStatus.ACTIVE, List.of(V10, V11),
                Set.of(), null, quota);
    }

    @Test
    void dedicatedGroupQuotaWins_andIsMarkedGroupScoped() {
        QuotaResolver resolver = new QuotaResolver(rateConfig());
        QuotaResolution q = resolver.resolve("caller-a", group("orders", new QuotaPolicy(2, 1_000)), V10);
        assertTrue(q.groupScoped());
        assertEquals(2, q.policy().limit());
        assertEquals("orders", q.groupId());
        assertTrue(q.basis().contains("interface-group quota"));
        System.out.println("[QuotaTest] 组自有配额: " + q.basis());
    }

    @Test
    void noGroupQuotaFallsBackToCallerVersionPolicy_butStaysGroupIsolated() {
        QuotaResolver resolver = new QuotaResolver(rateConfig());
        QuotaResolution q = resolver.resolve("caller-a", group("reports", null), V10);
        assertFalse(q.groupScoped());
        assertEquals(5, q.policy().limit(), "回退到 caller-a|v1.0=5");
        assertEquals("reports", q.groupId(), "即便回退，计数维度仍带接口组，与其他组隔离");
        assertTrue(q.basis().contains("fallback"));
        System.out.println("[QuotaTest] 组回退: " + q.basis());
    }

    @Test
    void fallbackCallerAsteriskAppliesPerGroup() {
        QuotaResolver resolver = new QuotaResolver(rateConfig());
        QuotaResolution q = resolver.resolve("caller-b", group("reports", null), V11);
        assertFalse(q.groupScoped());
        assertEquals(3, q.policy().limit());
        assertEquals(2_000, q.policy().windowMillis());
    }

    @Test
    void unregisteredCallerAndNoGroupQuota_neverUnlimited() {
        QuotaResolver resolver = new QuotaResolver(rateConfig());
        QuotaResolution q = resolver.resolve("stranger", group("reports", null), V10);
        assertNotNull(q.policy());
        assertEquals(1, q.policy().limit(), "未登记调用方落到兜底默认 1/s，绝不无限放行");
        System.out.println("[QuotaTest] 兜底: " + q.basis());
    }

    @Test
    void ungroupedRouteUsesInternalGroupIdAndFallbackPolicy() {
        QuotaResolver resolver = new QuotaResolver(rateConfig());
        QuotaResolution q = resolver.resolve("caller-a", null, V10);
        assertEquals(QuotaResolver.UNGROUPED, q.groupId());
        assertEquals(5, q.policy().limit());
    }
}
