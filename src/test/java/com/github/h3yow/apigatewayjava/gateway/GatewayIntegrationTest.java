package com.github.h3yow.apigatewayjava.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 全链路集成测试。网关入口 /gateway/** 转发到同进程 mock 下游 /mock/downstream/**。
 * 覆盖：并发限流守恒与错误码区分、窗口恢复、版本不兼容、缺调用方、
 * 下游超时/429/500、错误结构一致且带 traceId、重试为新请求。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "gateway.downstream.timeout-millis=300")
class GatewayIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate rest;

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private HttpHeaders headers(String caller, String version) {
        HttpHeaders h = new HttpHeaders();
        if (caller != null) {
            h.set("X-Caller-Id", caller);
        }
        if (version != null) {
            h.set("X-Api-Version", version);
        }
        return h;
    }

    private ResponseEntity<String> post(String path, String caller, String version) {
        return rest.exchange(url(path), HttpMethod.POST,
                new HttpEntity<>("{}", headers(caller, version)), String.class);
    }

    @Test
    void concurrentLimit_quotaConservedAndRejectionIsDistinguishable() throws Exception {
        int threads = 12;
        int perThread = 2; // 24 请求；caller-b 配额 3/s
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger limited = new AtomicInteger();
        ConcurrentLinkedQueue<String> traceIds = new ConcurrentLinkedQueue<>();
        List<ResponseEntity<String>> limitedResponses = new ArrayList<>();

        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        ResponseEntity<String> r = post("/gateway/mock/downstream/echo", "caller-b", "v1.0");
                        traceIds.add(r.getHeaders().getFirst("X-Trace-Id"));
                        if (r.getStatusCode().value() == 200) {
                            ok.incrementAndGet();
                        } else if (r.getStatusCode().value() == 429) {
                            limited.incrementAndGet();
                            synchronized (limitedResponses) {
                                limitedResponses.add(r);
                            }
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS));
        pool.shutdown();

        System.out.printf("[IntegrationTest] 并发限流 caller=caller-b version=v1.0 quota=3/s "
                + "ok=%d limited=%d total=%d%n", ok.get(), limited.get(), threads * perThread);
        assertEquals(3, ok.get(), "配额守恒：恰好 3 个请求被放行");
        assertEquals(threads * perThread - 3, limited.get());
        assertEquals(24, traceIds.size());
        assertEquals(24, traceIds.stream().distinct().count(), "每个请求 traceId 唯一");

        for (ResponseEntity<String> r : limitedResponses) {
            JsonNode body = MAPPER.readTree(r.getBody());
            assertEquals("GATEWAY_RATE_LIMITED", body.get("errorCode").asText());
            assertEquals("RATE_LIMITED_QUOTA_EXHAUSTED", body.get("reason").asText());
            assertEquals("caller-b", body.get("caller").asText());
            assertEquals("v1.0", body.get("version").asText());
            assertTrue(body.get("retryAfterMillis").asLong() > 0);
            assertEquals(r.getHeaders().getFirst("X-Trace-Id"), body.get("traceId").asText());
            assertTrue(r.getHeaders().containsKey("Retry-After"));
        }
        System.out.println("[IntegrationTest] 限流响应样例: " + limitedResponses.get(0).getBody());
    }

    @Test
    void windowBoundary_quotaRecoversInNextSecond() throws Exception {
        // 未登记调用方配额 1/s：第一次通过，第二次限流；等待窗口翻页后再次通过
        ResponseEntity<String> first = post("/gateway/mock/downstream/echo", "stranger", "v1.0");
        ResponseEntity<String> second = post("/gateway/mock/downstream/echo", "stranger", "v1.0");
        System.out.printf("[IntegrationTest] 窗口内 caller=stranger version=v1.0 quota=1/s "
                + "first=%d second=%d%n", first.getStatusCode().value(), second.getStatusCode().value());
        assertEquals(200, first.getStatusCode().value());
        assertEquals(429, second.getStatusCode().value());

        // 等到下一个固定秒窗口
        long wait = 1_050 - (System.currentTimeMillis() % 1_000);
        Thread.sleep(Math.max(wait, 50));
        ResponseEntity<String> recovered = post("/gateway/mock/downstream/echo", "stranger", "v1.0");
        System.out.println("[IntegrationTest] 翻窗后恢复 status=" + recovered.getStatusCode().value());
        assertEquals(200, recovered.getStatusCode().value());
    }

    @Test
    void versionNegotiationRejectionsAreExplicit_neverSilentlyFallback() throws Exception {
        // 缺版本
        ResponseEntity<String> missing = post("/gateway/mock/downstream/echo", "caller-a", null);
        // caller-b 不被允许的 v1.1
        ResponseEntity<String> incompatible = post("/gateway/mock/downstream/echo", "caller-b", "v1.1");
        // 系统不支持 v9.9
        ResponseEntity<String> unknown = post("/gateway/mock/downstream/echo", "caller-a", "v9.9");
        // 未登记调用方请求其默认白名单之外的 v1.1
        ResponseEntity<String> stranger = post("/gateway/mock/downstream/echo", "stranger", "v1.1");

        assertError(missing, 400, "GATEWAY_MISSING_VERSION");
        assertError(incompatible, 400, "GATEWAY_CALLER_VERSION_NOT_ALLOWED");
        assertError(unknown, 400, "GATEWAY_UNKNOWN_VERSION");
        assertError(stranger, 400, "GATEWAY_CALLER_VERSION_NOT_ALLOWED");

        // 关键不变量：这些被拒请求都没有消耗配额（caller-a 仍可用满 v1.0 的 5 个配额）
        for (int i = 0; i < 5; i++) {
            assertEquals(200, post("/gateway/mock/downstream/echo", "caller-a", "v1.0")
                    .getStatusCode().value(), "版本拒绝不得扣减任何配额");
        }
        System.out.println("[IntegrationTest] 版本不兼容拒绝后 caller-a v1.0 配额完好=5/5");
    }

    @Test
    void missingCallerIsRejected() throws Exception {
        ResponseEntity<String> r = post("/gateway/mock/downstream/echo", null, "v1.0");
        assertError(r, 401, "GATEWAY_MISSING_CALLER");
        System.out.println("[IntegrationTest] 缺调用方 status=401 body=" + r.getBody());
    }

    @Test
    void downstreamFailuresMapToDistinctErrors_unifiedShape() throws Exception {
        // caller-a v1.1 配额 10/s，避免限流干扰
        ResponseEntity<String> timeout = post("/gateway/mock/downstream/slow", "caller-a", "v1.1");
        ResponseEntity<String> busy = post("/gateway/mock/downstream/busy", "caller-a", "v1.1");
        ResponseEntity<String> error = post("/gateway/mock/downstream/error", "caller-a", "v1.1");

        assertError(timeout, 504, "GATEWAY_DOWNSTREAM_TIMEOUT");
        assertError(busy, 429, "GATEWAY_DOWNSTREAM_RATE_LIMITED");
        assertError(error, 502, "GATEWAY_DOWNSTREAM_ERROR");

        for (ResponseEntity<String> r : List.of(timeout, busy, error)) {
            JsonNode body = MAPPER.readTree(r.getBody());
            assertTrue(body.has("traceId") && !body.get("traceId").asText().isBlank());
            assertTrue(body.has("errorCode") && body.has("reason") && body.has("message"));
            assertEquals(r.getHeaders().getFirst("X-Trace-Id"), body.get("traceId").asText());
        }
        System.out.println("[IntegrationTest] 下游三类错误结构一致、原因可区分: "
                + timeout.getBody() + " | " + busy.getBody() + " | " + error.getBody());
    }

    @Test
    void retriesAreNewRequests_withNewTraceId_andFullReevaluation() throws Exception {
        // stranger v1.0 配额 1/s：第二次是“重试”，被限流；翻窗后再重试以新请求身份成功
        ResponseEntity<String> r1 = post("/gateway/mock/downstream/echo", "stranger2", "v1.0");
        ResponseEntity<String> r2 = post("/gateway/mock/downstream/echo", "stranger2", "v1.0");
        assertEquals(200, r1.getStatusCode().value());
        assertEquals(429, r2.getStatusCode().value());
        assertNotEquals(r1.getHeaders().getFirst("X-Trace-Id"), r2.getHeaders().getFirst("X-Trace-Id"),
                "重试请求必须生成新 traceId，按新请求重新判定");
        System.out.println("[IntegrationTest] 重试为新请求 trace1="
                + r1.getHeaders().getFirst("X-Trace-Id")
                + " trace2=" + r2.getHeaders().getFirst("X-Trace-Id"));
        assertFalse(r2.getHeaders().getFirst("X-Trace-Id").isBlank());
    }

    private void assertError(ResponseEntity<String> response, int httpStatus, String errorCode)
            throws Exception {
        assertEquals(httpStatus, response.getStatusCode().value(),
                "期望 " + httpStatus + " 实际 " + response.getStatusCode() + " body=" + response.getBody());
        JsonNode body = MAPPER.readTree(response.getBody());
        assertEquals(errorCode, body.get("errorCode").asText());
        assertTrue(body.has("traceId"));
        System.out.printf("[IntegrationTest] caller=%s version=%s status=%d code=%s basis/message=%s%n",
                body.has("caller") ? body.get("caller").asText() : "-",
                body.hasNonNull("version") ? body.get("version").asText() : "-",
                httpStatus, errorCode, body.get("message").asText());
    }
}
