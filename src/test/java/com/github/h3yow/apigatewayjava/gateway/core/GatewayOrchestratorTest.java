package com.github.h3yow.apigatewayjava.gateway.core;

import com.github.h3yow.apigatewayjava.gateway.MutableClock;
import com.github.h3yow.apigatewayjava.gateway.downstream.DownstreamClient;
import com.github.h3yow.apigatewayjava.gateway.downstream.DownstreamException;
import com.github.h3yow.apigatewayjava.gateway.group.ApiGroupConfig;
import com.github.h3yow.apigatewayjava.gateway.group.GroupAuthorizer;
import com.github.h3yow.apigatewayjava.gateway.group.GroupQuotaResolver;
import com.github.h3yow.apigatewayjava.gateway.group.RouteRegistry;
import com.github.h3yow.apigatewayjava.gateway.model.ApiVersion;
import com.github.h3yow.apigatewayjava.gateway.model.ErrorSource;
import com.github.h3yow.apigatewayjava.gateway.model.GatewayRequest;
import com.github.h3yow.apigatewayjava.gateway.model.GatewayResponse;
import com.github.h3yow.apigatewayjava.gateway.model.QuotaPolicy;
import com.github.h3yow.apigatewayjava.gateway.model.RejectReason;
import com.github.h3yow.apigatewayjava.gateway.ratelimit.RateLimitConfig;
import com.github.h3yow.apigatewayjava.gateway.ratelimit.RateLimiter;
import com.github.h3yow.apigatewayjava.gateway.version.VersionNegotiator;
import com.github.h3yow.apigatewayjava.gateway.model.VersionPolicy;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 编排链路单测：使用真实策略组件 + 假下游 + 可控时钟，
 * 验证“拒绝不扣减、放行只扣一次、下游失败不退还、本地/下游来源不混淆、traceId 贯穿”。
 */
class GatewayOrchestratorTest {

    private static final ApiVersion V10 = new ApiVersion(1, 0);
    private static final ApiVersion V11 = new ApiVersion(1, 1);

    /** 可编排结果的假下游，并记录被调用次数。 */
    static final class FakeDownstream extends DownstreamClient {
        final AtomicInteger invocations = new AtomicInteger();
        volatile RejectReason failure;

        FakeDownstream() {
            super("http://localhost:0", 300);
        }

        @Override
        public GatewayResponse invoke(GatewayRequest request, ApiVersion routedVersion) {
            invocations.incrementAndGet();
            if (failure != null) {
                throw new DownstreamException(failure,
                        "fake downstream failure traceId=" + request.traceId());
            }
            return new GatewayResponse(200, "{\"ok\":true}");
        }
    }

    private record Harness(GatewayOrchestrator orchestrator, RateLimiter rateLimiter,
                           FakeDownstream downstream) {
    }

    private Harness harness() {
        return harness(VersionNegotiator.defaultLocalNegotiator());
    }

    private Harness harness(VersionNegotiator vn) {
        ApiGroupConfig groups = ApiGroupConfig.defaultLocalConfig(List.of(V10, V11));
        Map<String, QuotaPolicy> callerQuotas = new HashMap<>();
        callerQuotas.put(RateLimitConfig.key(RateLimitConfig.DEFAULT_CALLER, RateLimitConfig.ANY_VERSION),
                new QuotaPolicy(1, 1_000));
        callerQuotas.put(RateLimitConfig.key("caller-a", "v1.0"), new QuotaPolicy(5, 1_000));
        RateLimitConfig callers = new RateLimitConfig(callerQuotas);
        GroupQuotaResolver resolver = new GroupQuotaResolver(groups, callers, Map.of());
        RateLimiter rl = new RateLimiter(resolver, new MutableClock(10_000));
        FakeDownstream ds = new FakeDownstream();
        GatewayOrchestrator orch = new GatewayOrchestrator(vn, new RouteRegistry(groups),
                groups::policyFor, new GroupAuthorizer(), rl, ds);
        return new Harness(orch, rl, ds);
    }

    private GatewayRequest req(String caller, String version, String method, String path) {
        return new GatewayRequest("trace-1", caller,
                version == null ? null : ApiVersion.parse(version), method, path, "{}");
    }

    @Test
    void localRejectionsNeverConsumeQuota_orInvokeDownstream() {
        Harness h = harness();

        // 调用方级版本不兼容（caller-b 不允许 v1.1）
        GatewayException e1 = assertThrows(GatewayException.class,
                () -> h.orchestrator().handle(req("caller-b", "v1.1", "POST", "/mock/downstream/orders/create")));
        assertSame(RejectReason.CALLER_VERSION_NOT_ALLOWED, e1.reason());

        // 接口组版本不开放（orders 只开 v1.0）
        GatewayException e2 = assertThrows(GatewayException.class,
                () -> h.orchestrator().handle(req("caller-a", "v1.1", "POST", "/mock/downstream/orders/create")));
        assertSame(RejectReason.GROUP_VERSION_NOT_ALLOWED, e2.reason());

        // 组暂停 / 未开放 / 灰度不放行
        assertSame(RejectReason.GROUP_PAUSED, assertThrows(GatewayException.class,
                () -> h.orchestrator().handle(req("caller-a", "v1.0", "POST", "/mock/downstream/reports/daily")))
                .reason());
        assertSame(RejectReason.GROUP_NOT_OPENED, assertThrows(GatewayException.class,
                () -> h.orchestrator().handle(req("caller-a", "v1.1", "POST", "/mock/downstream/ledger/v2")))
                .reason());

        // 路由 404 / 405
        assertSame(RejectReason.ROUTE_NOT_FOUND, assertThrows(GatewayException.class,
                () -> h.orchestrator().handle(req("caller-a", "v1.0", "POST", "/nope"))).reason());
        assertSame(RejectReason.METHOD_NOT_ALLOWED, assertThrows(GatewayException.class,
                () -> h.orchestrator().handle(req("caller-a", "v1.0", "GET", "/mock/downstream/orders/create")))
                .reason());

        // 灰度：caller-c 在调用方级允许 v1.1，但不在 beta-ai 灰度名单 → 组级灰度拒绝
        VersionNegotiator vn = new VersionNegotiator(List.of(V10, V11), new java.util.LinkedHashMap<>(
                Map.of(
                        "caller-a", new VersionPolicy("caller-a", List.of(V10, V11), false),
                        "caller-c", new VersionPolicy("caller-c", List.of(V11), false),
                        VersionNegotiator.DEFAULT_CALLER,
                        new VersionPolicy(VersionNegotiator.DEFAULT_CALLER, List.of(V10), false))));
        Harness h2 = harness(vn);
        assertSame(RejectReason.GROUP_CANARY_NOT_ALLOWED, assertThrows(GatewayException.class,
                () -> h2.orchestrator().handle(req("caller-c", "v1.1", "POST", "/mock/downstream/ai/chat")))
                .reason());
        assertEquals(0, h2.downstream().invocations.get());

        // 全部是本地错误，且没有调用下游、没有扣任何组配额
        for (RejectReason r : List.of(e1.reason(), e2.reason(), RejectReason.GROUP_PAUSED,
                RejectReason.GROUP_NOT_OPENED, RejectReason.GROUP_CANARY_NOT_ALLOWED,
                RejectReason.ROUTE_NOT_FOUND, RejectReason.METHOD_NOT_ALLOWED)) {
            assertSame(ErrorSource.LOCAL, r.source());
        }
        assertEquals(0, h.downstream().invocations.get());
        assertEquals(0, h.rateLimiter().currentUsed("caller-a", V10, "orders"));
        assertEquals(0, h.rateLimiter().currentUsed("caller-a", V11, "beta-ai"));
        System.out.println("[OrchestratorTest] 本地拒绝均未扣减配额、未调用下游");
    }

    @Test
    void passedRequestConsumesExactlyOnce_andCarriesGroupAndTrace() {
        Harness h = harness();
        GatewayRequest request = req("caller-a", "v1.0", "POST", "/mock/downstream/orders/create");

        GatewayResponse response = h.orchestrator().handle(request);
        assertEquals(200, response.status());
        assertEquals(1, h.downstream().invocations.get());
        assertEquals(1, h.rateLimiter().currentUsed("caller-a", V10, "orders"));
        System.out.println("[OrchestratorTest] 放行扣减一次 orders used=1");
    }

    @Test
    void downstreamFailureDoesNotRefund_andIsClassifiedDownstream() {
        Harness h = harness();
        h.downstream().failure = RejectReason.DOWNSTREAM_TIMEOUT;

        GatewayException ex = assertThrows(GatewayException.class,
                () -> h.orchestrator().handle(req("caller-a", "v1.0", "POST", "/mock/downstream/payments/charge")));
        assertSame(RejectReason.DOWNSTREAM_TIMEOUT, ex.reason());
        assertSame(ErrorSource.DOWNSTREAM, ex.reason().source());
        // 配额已消费且不退还（payments 组配额 4/s）
        assertEquals(1, h.rateLimiter().currentUsed("caller-a", V10, "payments"));
        assertEquals(1, h.downstream().invocations.get());
        assertTrue(ex.getMessage().contains("traceId=trace-1"));

        // 下游 429 与下游 5xx 也归类为 DOWNSTREAM，且与本地限流原因不同
        h.downstream().failure = RejectReason.DOWNSTREAM_RATE_LIMITED;
        assertSame(RejectReason.DOWNSTREAM_RATE_LIMITED, assertThrows(GatewayException.class,
                () -> h.orchestrator().handle(req("caller-a", "v1.0", "POST", "/mock/downstream/payments/charge")))
                .reason());
        h.downstream().failure = RejectReason.DOWNSTREAM_ERROR;
        assertSame(RejectReason.DOWNSTREAM_ERROR, assertThrows(GatewayException.class,
                () -> h.orchestrator().handle(req("caller-a", "v1.0", "POST", "/mock/downstream/payments/charge")))
                .reason());
        assertEquals(3, h.rateLimiter().currentUsed("caller-a", V10, "payments"),
                "三次下游失败各消费一次配额且均不退还");
        System.out.println("[OrchestratorTest] 下游失败不退还配额，来源 DOWNSTREAM，与本地 429 区分");
    }

    @Test
    void localRateLimitTriggersBeforeDownstream_withRetryAfter() {
        Harness h = harness();
        // orders 组配额 2/s：第三次本地拦截，下游只被调用 2 次
        h.orchestrator().handle(req("caller-a", "v1.0", "POST", "/mock/downstream/orders/create"));
        h.orchestrator().handle(req("caller-a", "v1.0", "POST", "/mock/downstream/orders/create"));
        GatewayException ex = assertThrows(GatewayException.class,
                () -> h.orchestrator().handle(req("caller-a", "v1.0", "POST", "/mock/downstream/orders/create")));
        assertSame(RejectReason.RATE_LIMITED_QUOTA_EXHAUSTED, ex.reason());
        assertSame(ErrorSource.LOCAL, ex.reason().source());
        assertTrue(ex.retryAfterMillis() > 0);
        assertEquals(2, h.downstream().invocations.get());
        System.out.printf("[OrchestratorTest] 本地限流先于下游 retryAfterMillis=%d%n",
                ex.retryAfterMillis());
    }
}
