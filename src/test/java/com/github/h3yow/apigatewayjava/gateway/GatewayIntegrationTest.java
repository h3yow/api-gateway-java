package com.github.h3yow.apigatewayjava.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.h3yow.apigatewayjava.gateway.config.AdjustableClock;
import org.junit.jupiter.api.AfterEach;
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
 *
 * <p>时间确定性：所有涉及配额/窗口的用例都先把 {@link AdjustableClock} 冻结在
 * 各自独占的瞬时（间隔 60s，窗口互不重叠），窗口边界由测试主动推进，
 * 不依赖真实等待，因此任意时刻、任意机器负载下运行结论一致。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "gateway.downstream.timeout-millis=300")
class GatewayIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 各用例独占的冻结瞬时：彼此间隔 60s，保证任何维度的 1s 窗口都不重叠。 */
    private static final long T_CONCURRENT = 1_700_000_000_000L;
    private static final long T_WINDOW = 1_700_000_060_000L;
    private static final long T_RETRY = 1_700_000_120_000L;
    private static final long T_VERSION = 1_700_000_180_000L;
    private static final long T_DOWNSTREAM_FAIL = 1_700_000_240_000L;
    private static final long T_GROUP_ISOLATION = 1_700_000_300_000L;
    private static final long T_GROUP_REJECT = 1_700_000_360_000L;
    private static final long T_ROUTE = 1_700_000_420_000L;
    private static final long T_DOWNSTREAM_SOURCE = 1_700_000_480_000L;

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate rest;

    @Autowired
    AdjustableClock clock;

    /** 每个用例结束后恢复真实时间，避免冻结状态泄漏到共享上下文的其它测试类。 */
    @AfterEach
    void releaseClock() {
        clock.followSystem();
    }

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
        // 冻结时间：24 个并发请求必然落在同一个 1s 窗口内，与机器负载、真实时刻无关
        clock.freezeAt(T_CONCURRENT);
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
        // 冻结在窗口 [T_WINDOW, T_WINDOW+1000) 的最后一毫秒：边界位置由测试精确指定
        clock.freezeAt(T_WINDOW + 999);
        // 未登记调用方配额 1/s：第一次通过，第二次限流且 retryAfter 恰好指向窗口末
        ResponseEntity<String> first = post("/gateway/mock/downstream/echo", "stranger", "v1.0");
        ResponseEntity<String> second = post("/gateway/mock/downstream/echo", "stranger", "v1.0");
        System.out.printf("[IntegrationTest] 窗口内 caller=stranger version=v1.0 quota=1/s "
                + "first=%d second=%d%n", first.getStatusCode().value(), second.getStatusCode().value());
        assertEquals(200, first.getStatusCode().value());
        assertEquals(429, second.getStatusCode().value());
        JsonNode limited = MAPPER.readTree(second.getBody());
        assertEquals(1L, limited.get("retryAfterMillis").asLong(), "距窗口结束恰好 1ms");

        // 主动把时钟推进 1ms 跨过窗口边界（不真实等待）：计数归零、配额恢复
        clock.advanceMillis(1);
        ResponseEntity<String> recovered = post("/gateway/mock/downstream/echo", "stranger", "v1.0");
        System.out.println("[IntegrationTest] 翻窗后恢复 status=" + recovered.getStatusCode().value());
        assertEquals(200, recovered.getStatusCode().value());
    }

    @Test
    void versionNegotiationRejectionsAreExplicit_neverSilentlyFallback() throws Exception {
        clock.freezeAt(T_VERSION);
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
        clock.freezeAt(T_DOWNSTREAM_FAIL);
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
        // 冻结时间：两次请求必然落在同一窗口，不需要先对齐窗口起点、不真实等待
        clock.freezeAt(T_RETRY);
        String caller = "stranger-retry";
        ResponseEntity<String> r1 = post("/gateway/mock/downstream/echo", caller, "v1.0");
        ResponseEntity<String> r2 = post("/gateway/mock/downstream/echo", caller, "v1.0");
        assertEquals(200, r1.getStatusCode().value());
        assertEquals(429, r2.getStatusCode().value());
        assertNotEquals(r1.getHeaders().getFirst("X-Trace-Id"), r2.getHeaders().getFirst("X-Trace-Id"),
                "重试请求必须生成新 traceId，按新请求重新判定");
        System.out.println("[IntegrationTest] 重试为新请求 trace1="
                + r1.getHeaders().getFirst("X-Trace-Id")
                + " trace2=" + r2.getHeaders().getFirst("X-Trace-Id"));
        assertFalse(r2.getHeaders().getFirst("X-Trace-Id").isBlank());
    }

    // ============ 接口组：配额隔离 / 版本与上线状态 / 错误来源 ============

    @Test
    void apiGroupQuotaIsIsolatedFromOtherGroupsAndCallerQuota() throws Exception {
        clock.freezeAt(T_GROUP_ISOLATION);
        String caller = "grp-iso";
        String orders = "/gateway/mock/downstream/orders/create";

        // orders 组独立配额 2/s：第 3 次本地限流
        ResponseEntity<String> o1 = post(orders, caller, "v1.0");
        ResponseEntity<String> o2 = post(orders, caller, "v1.0");
        ResponseEntity<String> o3 = post(orders, caller, "v1.0");
        assertEquals(200, o1.getStatusCode().value());
        assertEquals(200, o2.getStatusCode().value());
        assertError(o3, 429, "GATEWAY_RATE_LIMITED");
        JsonNode limited = MAPPER.readTree(o3.getBody());
        assertEquals("LOCAL", limited.get("errorSource").asText());
        assertEquals("orders", limited.get("group").asText());
        assertTrue(limited.get("retryAfterMillis").asLong() > 0);
        assertEquals(o3.getHeaders().getFirst("X-Trace-Id"), limited.get("traceId").asText());
        assertTrue(o3.getHeaders().containsKey("Retry-After"));

        // orders 被打满不影响 default 组（echo 回退到该调用方×版本=默认 1/s）：
        // 同一调用方同一版本在另一组仍有独立额度
        ResponseEntity<String> echo = post("/gateway/mock/downstream/echo", caller, "v1.0");
        assertEquals(200, echo.getStatusCode().value(), "不同接口组额度独立，互不占用");

        // payments 组同样独立（组配额 4/s），前 4 次放行
        for (int i = 0; i < 4; i++) {
            assertEquals(200, post("/gateway/mock/downstream/payments/charge", caller, "v1.0").getStatusCode().value(),
                    "payments 组额度独立于 orders 组");
        }
        assertError(post("/gateway/mock/downstream/payments/charge", caller, "v1.0"),
                429, "GATEWAY_RATE_LIMITED");
        System.out.println("[IntegrationTest] 接口组配额隔离: orders=2/s 打满不影响 default/payments");
    }

    @Test
    void apiGroupRejectionReasonsAreDistinguishable_andNoQuotaConsumed() throws Exception {
        clock.freezeAt(T_GROUP_REJECT);
        // PAUSED
        ResponseEntity<String> paused = post("/gateway/mock/downstream/reports/daily", "grp-rej", "v1.0");
        assertError(paused, 503, "GATEWAY_API_GROUP_PAUSED");
        assertSource(paused, "LOCAL", "reports");

        // NOT_OPENED：caller-a 调用方级允许 v1.1，但 ledger-v2 尚未开放 → 组级 404
        ResponseEntity<String> notOpened = post("/gateway/mock/downstream/ledger/v2", "caller-a", "v1.1");
        assertError(notOpened, 404, "GATEWAY_API_GROUP_NOT_OPENED");
        assertSource(notOpened, "LOCAL", "ledger-v2");

        // CANARY：caller-a 在 beta-ai 灰度白名单内 → 放行
        ResponseEntity<String> canaryOk = post("/gateway/mock/downstream/ai/chat", "caller-a", "v1.1");
        assertEquals(200, canaryOk.getStatusCode().value(), "灰度白名单内调用方放行");

        // 组版本不开放：orders 只开 v1.0，caller-a 的 v1.1 在调用方级允许，但组级必须拒绝
        ResponseEntity<String> groupVersion = post("/gateway/mock/downstream/orders/create", "caller-a", "v1.1");
        assertError(groupVersion, 400, "GATEWAY_API_GROUP_VERSION_NOT_ALLOWED");
        assertSource(groupVersion, "LOCAL", "orders");

        // 组级版本拒绝不得消耗 orders 组 v1.0 配额（仍可打满 2 个）
        assertEquals(200, post("/gateway/mock/downstream/orders/create", "grp-rej2", "v1.0").getStatusCode().value());
        assertEquals(200, post("/gateway/mock/downstream/orders/create", "grp-rej2", "v1.0").getStatusCode().value());
        System.out.println("[IntegrationTest] PAUSED/NOT_OPENED/组版本拒绝 原因可区分且均为 LOCAL");
    }

    @Test
    void unknownRouteAndBadMethodAreLocalErrors_notDownstream() throws Exception {
        clock.freezeAt(T_ROUTE);
        // 未注册路径：本地 404，不转发下游
        ResponseEntity<String> notFound = post("/gateway/mock/does/not/exist", "grp-route", "v1.0");
        assertError(notFound, 404, "GATEWAY_ROUTE_NOT_FOUND");
        assertSource(notFound, "LOCAL", null);

        // GET 一个仅注册了 POST 的路由：本地 405
        HttpHeaders h = headers("grp-route", "v1.0");
        ResponseEntity<String> method = rest.exchange(url("/gateway/mock/downstream/orders/create"),
                HttpMethod.GET, new HttpEntity<>("{}", h), String.class);
        assertError(method, 405, "GATEWAY_METHOD_NOT_ALLOWED");
        assertSource(method, "LOCAL", "orders");

        // 这两类本地错误都不应消耗任何配额：grp-route 之后仍可用满 default 组 1/s
        assertEquals(200, post("/gateway/mock/downstream/echo", "grp-route", "v1.0")
                .getStatusCode().value(), "本地 404/405 不得扣减配额");
        System.out.println("[IntegrationTest] 路由不存在=本地404，方法不支持=本地405，均未转发/扣减");
    }

    @Test
    void downstreamErrorsAreMarkedDownstream_andNoRetryAfter() throws Exception {
        clock.freezeAt(T_DOWNSTREAM_SOURCE);
        ResponseEntity<String> busy = post("/gateway/mock/downstream/busy", "caller-a", "v1.1");
        assertError(busy, 429, "GATEWAY_DOWNSTREAM_RATE_LIMITED");
        JsonNode body = MAPPER.readTree(busy.getBody());
        assertEquals("DOWNSTREAM", body.get("errorSource").asText());
        assertFalse(busy.getHeaders().containsKey("Retry-After"), "下游 429 不由网关给重试窗口");
        // 与本地 429 错误码不同
        assertEquals(200, post("/gateway/mock/downstream/orders/create", "grp-src", "v1.0").getStatusCode().value());
        assertEquals(200, post("/gateway/mock/downstream/orders/create", "grp-src", "v1.0").getStatusCode().value());
        ResponseEntity<String> localLimited = post("/gateway/mock/downstream/orders/create", "grp-src", "v1.0");
        assertError(localLimited, 429, "GATEWAY_RATE_LIMITED");
        assertEquals("LOCAL", MAPPER.readTree(localLimited.getBody()).get("errorSource").asText());
        System.out.println("[IntegrationTest] 下游429 errorSource=DOWNSTREAM 与本地429 LOCAL 可区分");
    }

    @Test
    void singleTraceId_consistentBetweenHeaderAndBody() throws Exception {
        ResponseEntity<String> r = post("/gateway/mock/downstream/reports/daily", "grp-trace", "v1.0");
        String headerTrace = r.getHeaders().getFirst("X-Trace-Id");
        JsonNode body = MAPPER.readTree(r.getBody());
        assertFalse(headerTrace == null || headerTrace.isBlank());
        assertEquals(headerTrace, body.get("traceId").asText(), "响应头与错误体 traceId 必须一致");
        // 整个响应只保留这一个追踪标识头
        assertEquals(List.of(headerTrace), r.getHeaders().get("X-Trace-Id"));
        System.out.println("[IntegrationTest] 唯一 traceId=" + headerTrace + " 头/体一致");
    }

    private void assertSource(ResponseEntity<String> response, String source, String group)
            throws Exception {
        JsonNode body = MAPPER.readTree(response.getBody());
        assertEquals(source, body.get("errorSource").asText(), "body=" + response.getBody());
        if (group != null) {
            assertEquals(group, body.get("group").asText());
        }
        assertEquals(response.getHeaders().getFirst("X-Trace-Id"), body.get("traceId").asText());
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
