package com.github.h3yow.apigatewayjava.gateway;

import com.github.h3yow.apigatewayjava.gateway.config.GatewayProperties;
import com.github.h3yow.apigatewayjava.gateway.downstream.DownstreamException;
import com.github.h3yow.apigatewayjava.gateway.downstream.DownstreamFailure;
import com.github.h3yow.apigatewayjava.gateway.downstream.DownstreamInvoker;
import com.github.h3yow.apigatewayjava.gateway.downstream.DownstreamResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 全栈集成测试：真实 Spring MVC（过滤器/控制器/异常处理/JSON 序列化），
 * 但下游调用与时钟由确定性替身提供。
 */
@SpringBootTest(properties = {
        "gateway.callers.default.quota=5",
        "gateway.callers.default.window-millis=60000",
        "gateway.callers.default.versions=v1",
        "gateway.callers.premium-a.quota=2",
        "gateway.callers.premium-a.window-millis=1000",
        "gateway.callers.premium-a.versions=v1,v2"
})
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class GatewayIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(GatewayIntegrationTest.class);

    /** 测试期间全局可控时钟，默认固定在纪元 10_000ms。 */
    static final AtomicReference<Clock> CONTROLLED_CLOCK = new AtomicReference<>(
            Clock.fixed(Instant.ofEpochMilli(10_000), ZoneOffset.UTC));

    /** 下游替身：可切换成功/各类失败。 */
    static final ScriptedDownstream SCRIPTED_DOWNSTREAM = new ScriptedDownstream();

    @TestConfiguration
    static class TestBeans {
        @Bean
        @Primary
        Clock controlledClock() {
            return new Clock() {
                @Override
                public ZoneOffset getZone() {
                    return ZoneOffset.UTC;
                }

                @Override
                public Clock withZone(java.time.ZoneId zone) {
                    return this;
                }

                @Override
                public Instant instant() {
                    return CONTROLLED_CLOCK.get().instant();
                }
            };
        }

        @Bean
        @Primary
        DownstreamInvoker scriptedDownstream() {
            return SCRIPTED_DOWNSTREAM;
        }
    }

    static class ScriptedDownstream implements DownstreamInvoker {
        volatile DownstreamFailure failure;
        volatile Long retryAfter;
        final AtomicInteger calls = new AtomicInteger();

        @Override
        public DownstreamResponse invoke(String path, String traceId) {
            calls.incrementAndGet();
            if (failure != null) {
                throw new DownstreamException(failure,
                        failure == DownstreamFailure.THROTTLED ? 429 : 500, retryAfter,
                        "scripted " + failure, null);
            }
            return new DownstreamResponse(200, "{\"ok\":true,\"traceId\":\"" + traceId + "\"}");
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @BeforeEach
    void reset() {
        SCRIPTED_DOWNSTREAM.failure = null;
        SCRIPTED_DOWNSTREAM.retryAfter = null;
        SCRIPTED_DOWNSTREAM.calls.set(0);
        CONTROLLED_CLOCK.set(Clock.fixed(Instant.ofEpochMilli(10_000), ZoneOffset.UTC));
    }

    @AfterEach
    void restore() {
        SCRIPTED_DOWNSTREAM.failure = null;
        CONTROLLED_CLOCK.set(Clock.fixed(Instant.ofEpochMilli(10_000), ZoneOffset.UTC));
    }

    @Test
    void successThenLocalRateLimitWithDistinguishableBody() throws Exception {
        mockMvc.perform(get("/gateway/items").header("X-Caller-Id", "premium-a").header("X-Api-Version", "v1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(header().exists("X-Trace-Id"));
        mockMvc.perform(get("/gateway/items").header("X-Caller-Id", "premium-a").header("X-Api-Version", "v1"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/gateway/items")
                        .header("X-Caller-Id", "premium-a")
                        .header("X-Api-Version", "v1")
                        .header("X-Trace-Id", "trace-int-3"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.errorCode").value("LOCAL_RATE_LIMITED"))
                .andExpect(jsonPath("$.reason").value("LOCAL_QUOTA_EXCEEDED"))
                .andExpect(jsonPath("$.caller").value("premium-a"))
                .andExpect(jsonPath("$.version").value("V1"))
                .andExpect(jsonPath("$.traceId").value("trace-int-3"))
                .andExpect(jsonPath("$.retryAfterMillis").isNumber())
                .andExpect(header().exists("Retry-After"));
        log.info("集成测试 调用方=premium-a 版本=v1 配额=2 判定=2xx,2xx,429(LOCAL_RATE_LIMITED) 依据=固定窗口配额守恒，错误体携带traceId与重试时间");
    }

    @Test
    void sameCallerV1AndV2HaveIndependentQuotasOverHttp() throws Exception {
        // v1 用满 2 个
        mockMvc.perform(get("/gateway/items").header("X-Caller-Id", "premium-a").header("X-Api-Version", "v1"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/gateway/items").header("X-Caller-Id", "premium-a").header("X-Api-Version", "v1"))
                .andExpect(status().isOk());
        // v1 第三个拒绝；v2 第一个仍应放行（维度独立）
        mockMvc.perform(get("/gateway/items").header("X-Caller-Id", "premium-a").header("X-Api-Version", "v1"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.errorCode").value("LOCAL_RATE_LIMITED"));
        mockMvc.perform(get("/gateway/items").header("X-Caller-Id", "premium-a").header("X-Api-Version", "v2"))
                .andExpect(status().isOk());
        log.info("集成测试 调用方=premium-a 版本=v1用满后拒绝, v2独立放行 依据=配额维度=调用方×版本");
    }

    @Test
    void unknownAndUnsupportedVersionsRejectedWithoutFallback() throws Exception {
        mockMvc.perform(get("/gateway/items").header("X-Caller-Id", "premium-a").header("X-Api-Version", "v9"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VERSION_UNKNOWN"));
        mockMvc.perform(get("/gateway/items").header("X-Caller-Id", "some-new-caller").header("X-Api-Version", "v2"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VERSION_NOT_SUPPORTED"));
        mockMvc.perform(get("/gateway/items").header("X-Caller-Id", "premium-a"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VERSION_MISSING"));
        int downstreamCalls = SCRIPTED_DOWNSTREAM.calls.get();
        assertTrue(downstreamCalls == 0, "版本拒绝不得触发下游调用，实际=" + downstreamCalls);
        log.info("集成测试 版本拒绝(v9/默认策略v2/缺失) 下游调用={} 依据=版本协商失败不回落、不扣配额", downstreamCalls);
    }

    @Test
    void downstreamTimeoutAndThrottleReturnConsistentErrorShape() throws Exception {
        SCRIPTED_DOWNSTREAM.failure = DownstreamFailure.TIMEOUT;
        mockMvc.perform(get("/gateway/items")
                        .header("X-Caller-Id", "premium-a")
                        .header("X-Api-Version", "v1")
                        .header("X-Trace-Id", "trace-to"))
                .andExpect(status().isGatewayTimeout())
                .andExpect(jsonPath("$.errorCode").value("DOWNSTREAM_TIMEOUT"))
                .andExpect(jsonPath("$.reason").value("TIMEOUT"))
                .andExpect(jsonPath("$.traceId").value("trace-to"));

        reset();
        SCRIPTED_DOWNSTREAM.failure = DownstreamFailure.THROTTLED;
        SCRIPTED_DOWNSTREAM.retryAfter = 2000L;
        mockMvc.perform(get("/gateway/items")
                        .header("X-Caller-Id", "premium-a")
                        .header("X-Api-Version", "v2")
                        .header("X-Trace-Id", "trace-429"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.errorCode").value("DOWNSTREAM_RATE_LIMITED"))
                .andExpect(jsonPath("$.reason").value("THROTTLED"))
                .andExpect(jsonPath("$.retryAfterMillis").value(2000))
                .andExpect(header().string("Retry-After", "2"))
                .andExpect(jsonPath("$.traceId").value("trace-429"));
        log.info("集成测试 下游TIMEOUT->504, THROTTLED->429(Retry-After=2s) 依据=统一错误结构，限流原因与本地配额可区分");
    }

    @Test
    void bothEntriesProduceIdenticalDecision() throws Exception {
        // /gateway/items 与 /gateway/echo 共用同一链路：配额跨入口共享同维度计数
        mockMvc.perform(get("/gateway/items").header("X-Caller-Id", "premium-a").header("X-Api-Version", "v1"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/gateway/echo").header("X-Caller-Id", "premium-a").header("X-Api-Version", "v1"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/gateway/echo").header("X-Caller-Id", "premium-a").header("X-Api-Version", "v1"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.errorCode").value("LOCAL_RATE_LIMITED"));
        log.info("集成测试 /items与/echo入口 配额=2 判定=2xx,2xx,429 依据=多入口共用同一判定链路与计数器");
    }
}
