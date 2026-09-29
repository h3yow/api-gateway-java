package com.github.h3yow.apigatewayjava.gateway.core;

import com.github.h3yow.apigatewayjava.gateway.downstream.DownstreamException;
import com.github.h3yow.apigatewayjava.gateway.downstream.DownstreamInvoker;
import com.github.h3yow.apigatewayjava.gateway.model.ApiVersion;
import com.github.h3yow.apigatewayjava.gateway.model.GatewayRequest;
import com.github.h3yow.apigatewayjava.gateway.model.GatewayResponse;
import com.github.h3yow.apigatewayjava.gateway.model.InterfaceGroupConfig;
import com.github.h3yow.apigatewayjava.gateway.model.InterfaceStatus;
import com.github.h3yow.apigatewayjava.gateway.model.QuotaPolicy;
import com.github.h3yow.apigatewayjava.gateway.model.RejectReason;
import com.github.h3yow.apigatewayjava.gateway.ratelimit.RateLimitConfig;
import com.github.h3yow.apigatewayjava.gateway.ratelimit.RateLimiter;
import com.github.h3yow.apigatewayjava.gateway.routing.InterfaceGroupGate;
import com.github.h3yow.apigatewayjava.gateway.routing.QuotaResolver;
import com.github.h3yow.apigatewayjava.gateway.routing.RouteTable;
import com.github.h3yow.apigatewayjava.gateway.version.VersionNegotiator;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 编排层单测：用桩下游 + 可控时钟，端到端验证接口组判定顺序、配额隔离与一次性扣减、
 * 以及 LOCAL / DOWNSTREAM 错误来源不混淆。
 */
class GatewayOrchestratorTest {

    private static final ApiVersion V10 = new ApiVersion(1, 0);
    private static final ApiVersion V11 = new ApiVersion(1, 1);

    /** 记录调用次数的桩下游，可被配置为抛出某类下游异常。 */
    private static final class StubDownstream implements DownstreamInvoker {
        final AtomicInteger invocations = new AtomicInteger();
        volatile RejectReason failure;

        @Override
        public GatewayResponse invoke(GatewayRequest request, ApiVersion routedVersion) {
            invocations.incrementAndGet();
            if (failure != null) {
                throw new DownstreamException(failure, "stub downstream failure: " + failure);
            }
            return new GatewayResponse(200, "{\"stub\":true}");
        }
    }

    private static final class FixedClock extends Clock {
        private volatile long millis = 10_000L;

        @Override
        public java.time.ZoneId getZone() {
            return java.time.ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            throw new UnsupportedOperationException();
        }

        @Override
        public java.time.Instant instant() {
            return java.time.Instant.ofEpochMilli(millis);
        }
    }

    private record Harness(GatewayOrchestrator orchestrator, StubDownstream downstream,
                           RateLimiter limiter, RateLimitConfig rateConfig) {
    }

    private Harness harness() {
        ApiVersion v10 = V10;
        ApiVersion v11 = V11;

        // 配额：caller-a v1.0=5/s、v1.1=10/s；caller-b *=3/s；兜底 1/s
        Map<String, QuotaPolicy> quotaMap = new HashMap<>();
        quotaMap.put(RateLimitConfig.key("caller-a", "v1.0"), new QuotaPolicy(5, 1_000));
        quotaMap.put(RateLimitConfig.key("caller-a", "v1.1"), new QuotaPolicy(10, 1_000));
        quotaMap.put(RateLimitConfig.key("caller-b", RateLimitConfig.ANY_VERSION), new QuotaPolicy(3, 1_000));
        quotaMap.put(RateLimitConfig.key(RateLimitConfig.DEFAULT_CALLER, RateLimitConfig.ANY_VERSION),
                new QuotaPolicy(1, 1_000));
        RateLimitConfig rateConfig = new RateLimitConfig(quotaMap);

        VersionNegotiator negotiator = VersionNegotiator.defaultLocalNegotiator();

        Map<String, InterfaceGroupConfig> groups = new LinkedHashMap<>();
        groups.put("/orders", new InterfaceGroupConfig("orders", InterfaceStatus.ACTIVE,
                List.of(v10, v11), Set.of(), null, new QuotaPolicy(2, 1_000)));
        groups.put("/reports", new InterfaceGroupConfig("reports", InterfaceStatus.ACTIVE,
                List.of(v10, v11), Set.of(), null, null));
        groups.put("/beta", new InterfaceGroupConfig("beta", InterfaceStatus.GRAY,
                List.of(v11), Set.of("caller-a"), null, new QuotaPolicy(2, 1_000)));
        groups.put("/legacy", new InterfaceGroupConfig("legacy", InterfaceStatus.PAUSED,
                List.of(v10), Set.of(), null, null));
        groups.put("/future", new InterfaceGroupConfig("future", InterfaceStatus.NOT_OPEN,
                List.of(v10, v11), Set.of(), null, null));
        RouteTable routeTable = new RouteTable(negotiator.supportedVersions(), groups);

        FixedClock clock = new FixedClock();
        RateLimiter limiter = new RateLimiter(rateConfig, clock);
        StubDownstream downstream = new StubDownstream();
        GatewayOrchestrator orchestrator = new GatewayOrchestrator(
                routeTable, new InterfaceGroupGate(), negotiator,
                new QuotaResolver(rateConfig), limiter, downstream);
        return new Harness(orchestrator, downstream, limiter, rateConfig);
    }

    private GatewayRequest req(String caller, ApiVersion version, String method, String path) {
        return new GatewayRequest("trace-" + System.nanoTime(), caller, version, method, path, "{}");
    }

    private GatewayException reject(Harness h, GatewayRequest request) {
        return assertThrows(GatewayException.class, () -> h.orchestrator().handle(request));
    }

    @Test
    void unknownRouteIsLocal404_andNeverCallsDownstream() {
        Harness h = harness();
        GatewayException ex = reject(h, req("caller-a", V10, "POST", "/nope/xxx"));
        assertEquals(RejectReason.ROUTE_NOT_FOUND, ex.reason());
        assertEquals("LOCAL", ex.reason().source().name());
        assertEquals(0, h.downstream().invocations.get());
    }

    @Test
    void pausedAndNotOpenAreDistinctLocalRejections_noQuotaDeduction() {
        Harness h = harness();
        GatewayException paused = reject(h, req("caller-a", V10, "POST", "/legacy/q"));
        GatewayException notOpen = reject(h, req("caller-a", V10, "POST", "/future/q"));
        assertEquals(RejectReason.INTERFACE_PAUSED, paused.reason());
        assertEquals(RejectReason.INTERFACE_NOT_OPEN, notOpen.reason());
        assertEquals(0, h.downstream().invocations.get());
        // 本地拒绝不扣配额：caller-a v1.0 回退组 reports 仍可用满 5
        for (int i = 0; i < 5; i++) {
            h.orchestrator().handle(req("caller-a", V10, "POST", "/reports/daily"));
        }
        assertEquals(5, h.limiter().currentUsed("caller-a", "reports", V10));
        assertEquals(0, h.limiter().currentUsed("caller-a", "legacy", V10));
    }

    @Test
    void grayStatus_isIndependentAcrossCallers() {
        Harness h = harness();
        // caller-a 在灰度白名单且版本 v1.1 开放
        h.orchestrator().handle(req("caller-a", V11, "POST", "/beta/q"));
        // caller-b 不在灰度白名单
        GatewayException denied = reject(h, req("caller-b", V11, "POST", "/beta/q"));
        assertEquals(RejectReason.INTERFACE_GRAY_NOT_ALLOWED, denied.reason());
        // caller-a 用 v1.0：系统/调用方允许，但 beta 组只开放 v1.1
        GatewayException wrongVersion = reject(h, req("caller-a", V10, "POST", "/beta/q"));
        assertEquals(RejectReason.INTERFACE_VERSION_NOT_OPEN, wrongVersion.reason());
        assertEquals(1, h.downstream().invocations.get(), "只有 caller-a v1.1 一次真正进入下游");
    }

    @Test
    void groupVersionRuleDoesNotLeakAcrossGroups() {
        Harness h = harness();
        // v1.0 在 beta 组不开放，但在 orders 组开放：两组结论互不影响
        GatewayException beta = reject(h, req("caller-a", V10, "POST", "/beta/q"));
        assertEquals(RejectReason.INTERFACE_VERSION_NOT_OPEN, beta.reason());
        h.orchestrator().handle(req("caller-a", V10, "POST", "/orders/1"));
        h.orchestrator().handle(req("caller-a", V10, "POST", "/orders/2"));
        GatewayException limited = reject(h, req("caller-a", V10, "POST", "/orders/3"));
        assertEquals(RejectReason.RATE_LIMITED_QUOTA_EXHAUSTED, limited.reason());
        assertTrue(limited.retryAfterMillis() > 0);
        // 被组版本拒绝的 beta 不消耗 orders 配额
        assertEquals(2, h.limiter().currentUsed("caller-a", "orders", V10));
        assertEquals(0, h.limiter().currentUsed("caller-a", "beta", V10));
    }

    @Test
    void methodNotAllowedIsLocal405() {
        Harness h = harness();
        GatewayException ex = reject(h, req("caller-a", V10, "GET", "/orders/1"));
        assertEquals(RejectReason.METHOD_NOT_ALLOWED, ex.reason());
        assertEquals(0, h.downstream().invocations.get());
    }

    @Test
    void quotaIsolationAcrossGroups_sameCallerSameVersion() {
        Harness h = harness();
        // orders 自有 2/s 耗尽
        h.orchestrator().handle(req("caller-a", V11, "POST", "/orders/1"));
        h.orchestrator().handle(req("caller-a", V11, "POST", "/orders/2"));
        GatewayException ordersLimited = reject(h, req("caller-a", V11, "POST", "/orders/3"));
        assertEquals(RejectReason.RATE_LIMITED_QUOTA_EXHAUSTED, ordersLimited.reason());
        // reports 回退 caller-a|v1.1=10/s，完全独立，仍可继续
        for (int i = 0; i < 10; i++) {
            h.orchestrator().handle(req("caller-a", V11, "POST", "/reports/daily"));
        }
        GatewayException reportsLimited = reject(h, req("caller-a", V11, "POST", "/reports/daily"));
        assertEquals(RejectReason.RATE_LIMITED_QUOTA_EXHAUSTED, reportsLimited.reason());
        assertEquals(2, h.limiter().currentUsed("caller-a", "orders", V11));
        assertEquals(10, h.limiter().currentUsed("caller-a", "reports", V11));
    }

    @Test
    void downstreamFailuresAreClassifiedDownstream_quotaNotRefunded_andDeductedOnce() {
        Harness h = harness();
        h.downstream().failure = RejectReason.DOWNSTREAM_TIMEOUT;
        GatewayException timeout = reject(h, req("caller-a", V10, "POST", "/orders/1"));
        assertEquals(RejectReason.DOWNSTREAM_TIMEOUT, timeout.reason());
        assertEquals("DOWNSTREAM", timeout.reason().source().name());
        // 配额已扣且不退还；同一请求下游只被调用一次
        assertEquals(1, h.downstream().invocations.get());
        assertEquals(1, h.limiter().currentUsed("caller-a", "orders", V10));

        // 下游 429 / 500 分类
        h.downstream().failure = RejectReason.DOWNSTREAM_RATE_LIMITED;
        GatewayException busy = reject(h, req("caller-a", V10, "POST", "/orders/2"));
        assertEquals(RejectReason.DOWNSTREAM_RATE_LIMITED, busy.reason());
        h.downstream().failure = RejectReason.DOWNSTREAM_ERROR;
        GatewayException err = reject(h, req("caller-a", V10, "POST", "/reports/daily"));
        assertEquals(RejectReason.DOWNSTREAM_ERROR, err.reason());
        // 第三次 orders 请求因组配额 2 已耗尽，应在本地被拦，下游不再被调用
        h.downstream().failure = null;
        GatewayException localLimited = reject(h, req("caller-a", V10, "POST", "/orders/3"));
        assertEquals(RejectReason.RATE_LIMITED_QUOTA_EXHAUSTED, localLimited.reason());
        assertEquals("LOCAL", localLimited.reason().source().name());
        assertEquals(3, h.downstream().invocations.get(), "本地限流请求不得进入下游");
    }

    @Test
    void localRejectionsNeverMasqueradeAsDownstream() {
        for (RejectReason expected : List.of(
                RejectReason.ROUTE_NOT_FOUND, RejectReason.METHOD_NOT_ALLOWED,
                RejectReason.INTERFACE_PAUSED, RejectReason.INTERFACE_NOT_OPEN,
                RejectReason.INTERFACE_GRAY_NOT_ALLOWED, RejectReason.INTERFACE_VERSION_NOT_OPEN,
                RejectReason.MISSING_VERSION, RejectReason.UNKNOWN_VERSION,
                RejectReason.CALLER_VERSION_NOT_ALLOWED, RejectReason.RATE_LIMITED_QUOTA_EXHAUSTED)) {
            assertEquals(com.github.h3yow.apigatewayjava.gateway.model.ErrorSource.LOCAL,
                    expected.source(), expected + " 必须归类为 LOCAL");
        }
        for (RejectReason expected : List.of(RejectReason.DOWNSTREAM_TIMEOUT,
                RejectReason.DOWNSTREAM_RATE_LIMITED, RejectReason.DOWNSTREAM_ERROR)) {
            assertEquals(com.github.h3yow.apigatewayjava.gateway.model.ErrorSource.DOWNSTREAM,
                    expected.source(), expected + " 必须归类为 DOWNSTREAM");
        }
    }
}
