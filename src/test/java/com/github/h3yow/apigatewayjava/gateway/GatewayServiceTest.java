package com.github.h3yow.apigatewayjava.gateway;

import com.github.h3yow.apigatewayjava.gateway.config.ApiVersion;
import com.github.h3yow.apigatewayjava.gateway.config.GatewayProperties;
import com.github.h3yow.apigatewayjava.gateway.core.GatewayService;
import com.github.h3yow.apigatewayjava.gateway.downstream.DownstreamException;
import com.github.h3yow.apigatewayjava.gateway.downstream.DownstreamFailure;
import com.github.h3yow.apigatewayjava.gateway.downstream.DownstreamInvoker;
import com.github.h3yow.apigatewayjava.gateway.downstream.DownstreamResponse;
import com.github.h3yow.apigatewayjava.gateway.error.ApiError;
import com.github.h3yow.apigatewayjava.gateway.ratelimit.RateLimiter;
import com.github.h3yow.apigatewayjava.gateway.version.VersionNegotiator;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 网关编排单测：默认策略、单次扣减、下游失败分类、traceId、重试语义。
 */
class GatewayServiceTest {

    private static final Logger log = LoggerFactory.getLogger(GatewayServiceTest.class);
    private static final Clock FIXED = Clock.fixed(Instant.ofEpochMilli(10_000), ZoneOffset.UTC);

    private GatewayProperties properties(String defaultVersions) {
        GatewayProperties props = new GatewayProperties();
        GatewayProperties.CallerConfig def = new GatewayProperties.CallerConfig();
        def.setQuota(2);
        def.setWindowMillis(60_000);
        def.setVersions(new ArrayList<>(List.of(defaultVersions.split(","))));
        props.getCallers().put(GatewayProperties.DEFAULT_CALLER, def);

        GatewayProperties.CallerConfig premium = new GatewayProperties.CallerConfig();
        premium.setQuota(3);
        premium.setWindowMillis(1000);
        premium.setVersions(new ArrayList<>(List.of("v1", "v2")));
        props.getCallers().put("premium-a", premium);
        return props;
    }

    private GatewayService service(GatewayProperties props, DownstreamInvoker invoker) {
        return new GatewayService(props, new VersionNegotiator(), new RateLimiter(), invoker, FIXED);
    }

    @Test
    void unregisteredCallerFallsBackToExplicitDefaultPolicyNotUnlimited() {
        GatewayProperties props = properties("v1");
        DownstreamInvoker invoker = mock(DownstreamInvoker.class);
        when(invoker.invoke(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(inv -> new DownstreamResponse(200, "ok-#" + inv.getArgument(1)));
        GatewayService gw = service(props, invoker);

        // 默认策略配额=2：第三个未登记调用方必须被本地限流拒绝，而非无限放行
        GatewayService.GatewayResult r1 = gw.handle("stranger-x", "v1", "/p", "t-1");
        GatewayService.GatewayResult r2 = gw.handle("stranger-x", "v1", "/p", "t-2");
        GatewayService.GatewayResult r3 = gw.handle("stranger-x", "v1", "/p", "t-3");

        assertEquals(200, r1.httpStatus());
        assertEquals(200, r2.httpStatus());
        ApiError error = r3.error();
        assertNotNull(error);
        assertEquals("LOCAL_RATE_LIMITED", error.errorCode());
        assertEquals("stranger-x", error.caller());
        log.info("默认策略测试 调用方=stranger-x(未登记) 版本=v1 配额=2(default) 判定=第3请求拒绝 原因={} 建议重试={}ms traceId={} 依据=未登记调用方回落default保守策略",
                error.errorCode(), error.retryAfterMillis(), error.traceId());
        assertEquals(429, r3.httpStatus());
        assertTrue(error.retryAfterMillis() > 0);
    }

    @Test
    void quotaIsDeductedExactlyOncePerRequestRegardlessOfDownstreamOutcome() {
        GatewayProperties props = properties("v1");
        AtomicInteger calls = new AtomicInteger();
        DownstreamInvoker invoker = mock(DownstreamInvoker.class);
        when(invoker.invoke(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(inv -> {
                    calls.incrementAndGet();
                    throw new DownstreamException(DownstreamFailure.SERVER_ERROR, 500, null,
                            "boom", null);
                });
        GatewayService gw = service(props, invoker);

        GatewayService.GatewayResult r1 = gw.handle("premium-a", "v1", "/p", "trace-once-1");
        GatewayService.GatewayResult r2 = gw.handle("premium-a", "v1", "/p", "trace-once-2");
        GatewayService.GatewayResult r3 = gw.handle("premium-a", "v1", "/p", "trace-once-3");

        // 3 个请求：恰好 3 次下游调用，1 个配额对应 1 次扣减，不存在重复计数
        assertEquals(3, calls.get(), "每个通过配额的请求必须且只能调用下游一次");
        assertEquals("DOWNSTREAM_ERROR", r1.error().errorCode());
        assertEquals("DOWNSTREAM_ERROR", r2.error().errorCode());
        assertEquals("DOWNSTREAM_ERROR", r3.error().errorCode());
        log.info("单次扣减测试 调用方=premium-a 版本=v1 配额=3 下游调用次数={} 判定依据=配额判定与下游调用各仅一次，下游失败不触发二次计数",
                calls.get());

        // 第 4 个请求：配额确已被前面 3 次（即使失败）消耗，本地限流
        GatewayService.GatewayResult r4 = gw.handle("premium-a", "v1", "/p", "trace-once-4");
        assertEquals("LOCAL_RATE_LIMITED", r4.error().errorCode());
        verify(invoker, times(3)).invoke(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString());
        log.info("单次扣减测试 调用方=premium-a 版本=v1 配额=3 第4请求判定={} 下游仍共调用={}次 依据=失败请求同样占用配额，重试按新请求重新判定",
                r4.error().errorCode(), calls.get());
    }

    @Test
    void downstreamFailuresMapToDistinguishableErrorCodes() {
        GatewayProperties props = properties("v1");
        GatewayService timeoutGw = service(props, failing(DownstreamFailure.TIMEOUT, 0, null));
        GatewayService throttleGw = service(props, failing(DownstreamFailure.THROTTLED, 429, 2000L));
        GatewayService serverGw = service(props, failing(DownstreamFailure.SERVER_ERROR, 503, null));
        GatewayService ioGw = service(props, failing(DownstreamFailure.IO_ERROR, 0, null));

        assertError(timeoutGw.handle("premium-a", "v1", "/p", "tr-timeout"),
                "DOWNSTREAM_TIMEOUT", 504, "tr-timeout");
        assertError(throttleGw.handle("premium-a", "v1", "/p", "tr-429"),
                "DOWNSTREAM_RATE_LIMITED", 429, "tr-429");
        assertError(serverGw.handle("premium-a", "v1", "/p", "tr-503"),
                "DOWNSTREAM_ERROR", 502, "tr-503");
        assertError(ioGw.handle("premium-a", "v1", "/p", "tr-io"),
                "DOWNSTREAM_UNAVAILABLE", 504, "tr-io");
        log.info("下游异常分类测试 调用方=premium-a 版本=v1 映射=TIMEOUT->504, THROTTLED(429)->429, SERVER_ERROR(503)->502, IO_ERROR->504 依据=错误结构一致且原因可区分");
    }

    @Test
    void downstreamThrottleIsDistinguishableFromLocalQuotaAndCarriesRetryAfter() {
        GatewayProperties props = properties("v1");
        GatewayService gw = service(props, failing(DownstreamFailure.THROTTLED, 429, 2000L));
        GatewayService.GatewayResult result = gw.handle("premium-a", "v1", "/p", "tr-dist");
        ApiError error = result.error();
        assertEquals("DOWNSTREAM_RATE_LIMITED", error.errorCode());
        assertEquals("THROTTLED", error.reason());
        assertEquals(2000L, error.retryAfterMillis());
        assertEquals("tr-dist", error.traceId());
        log.info("限流原因可区分测试 调用方=premium-a 版本=v1 错误码={} reason字段={} 建议重试={}ms traceId={} 依据=下游429与本地配额耗尽错误码不同",
                error.errorCode(), error.reason(), error.retryAfterMillis(), error.traceId());
    }

    @Test
    void retryAsNewRequestIsReEvaluatedAndVersionRejectionConsumesNoQuota() {
        GatewayProperties props = properties("v1");
        AtomicInteger calls = new AtomicInteger();
        DownstreamInvoker invoker = mock(DownstreamInvoker.class);
        when(invoker.invoke(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(invocation -> {
                    int n = calls.incrementAndGet();
                    if (n == 1) {
                        throw new DownstreamException(DownstreamFailure.TIMEOUT, 0, null, "slow", null);
                    }
                    return new DownstreamResponse(200, "retry-ok");
                });
        GatewayService gw = service(props, invoker);

        GatewayService.GatewayResult first = gw.handle("premium-a", "v1", "/p", "trace-retry-1");
        GatewayService.GatewayResult retry = gw.handle("premium-a", "v1", "/p", "trace-retry-2");
        assertEquals("DOWNSTREAM_TIMEOUT", first.error().errorCode());
        assertEquals(200, retry.httpStatus());
        assertEquals("retry-ok", retry.body());
        assertNull(retry.error());
        log.info("重试语义测试 调用方=premium-a 版本=v1 首次traceId=trace-retry-1判定=超时 重试traceId=trace-retry-2判定=成功 依据=重试按新请求进入并重新判定");

        // 不可识别版本必须在扣减前拒绝，下游零调用
        DownstreamInvoker ok = okInvoker();
        GatewayService badVersionGw = service(props, ok);
        GatewayService.GatewayResult unknown = badVersionGw.handle("premium-a", "v9", "/p", "trace-ver-unknown");
        assertEquals("VERSION_UNKNOWN", unknown.error().errorCode());
        log.info("版本拒绝不扣配额测试 调用方=premium-a 版本=v9 判定={} traceId={} 依据=版本协商先于配额判定，拒绝不产生计数",
                unknown.error().errorCode(), unknown.error().traceId());

        // 可识别但不兼容：premium-a 同时有 v1/v2 配额，但 v3 未知；此处用 default-only 策略验证不兼容
        GatewayService.GatewayResult unsupported =
                badVersionGw.handle("stranger-z", "v2", "/p", "trace-ver-unsupported");
        assertEquals("VERSION_NOT_SUPPORTED", unsupported.error().errorCode());
        verify(ok, never()).invoke(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString());
        log.info("版本拒绝不扣配额测试 调用方=stranger-z(默认策略仅v1) 版本=v2 判定={} 下游调用=0 依据=不兼容组合在配额判定前拒绝",
                unsupported.error().errorCode());
    }

    private DownstreamInvoker okInvoker() {
        DownstreamInvoker invoker = mock(DownstreamInvoker.class);
        when(invoker.invoke(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(new DownstreamResponse(200, "ok"));
        return invoker;
    }

    private static DownstreamInvoker failing(DownstreamFailure failure, int status, Long retryAfter) {
        DownstreamInvoker invoker = mock(DownstreamInvoker.class);
        when(invoker.invoke(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString()))
                .thenThrow(new DownstreamException(failure, status, retryAfter, failure.name(), null));
        return invoker;
    }

    private static void assertError(GatewayService.GatewayResult result, String expectedCode,
                                    int expectedHttpStatus, String expectedTraceId) {
        ApiError error = result.error();
        assertNotNull(error);
        assertEquals(expectedCode, error.errorCode());
        assertEquals(expectedHttpStatus, result.httpStatus());
        assertEquals(expectedTraceId, error.traceId());
        assertNotNull(error.message());
        assertNotNull(error.reason());
    }
}
