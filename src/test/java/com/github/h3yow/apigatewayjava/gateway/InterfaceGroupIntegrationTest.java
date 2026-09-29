package com.github.h3yow.apigatewayjava.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 接口组能力的端到端集成测试（真实 HTTP + 同进程业务 mock 下游）。
 * 时间相关断言由可控时钟驱动，无需真实等待。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "gateway.downstream.timeout-millis=300")
@Import(ControllableClockConfig.class)
class InterfaceGroupIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate rest;

    @Autowired
    ControllableClockConfig.MutableClock clock;

    @BeforeEach
    void freshWindow() {
        clock.setMillis(3_000_000_000_000L);
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private ResponseEntity<String> exchange(String path, String method, String caller, String version) {
        HttpHeaders h = new HttpHeaders();
        if (caller != null) {
            h.set("X-Caller-Id", caller);
        }
        if (version != null) {
            h.set("X-Api-Version", version);
        }
        return rest.exchange(url(path), HttpMethod.valueOf(method),
                new HttpEntity<>("{}", h), String.class);
    }

    private JsonNode body(ResponseEntity<String> r) throws Exception {
        return MAPPER.readTree(r.getBody());
    }

    private void assertLocalError(ResponseEntity<String> r, int httpStatus, String errorCode, String group)
            throws Exception {
        assertEquals(httpStatus, r.getStatusCode().value(), "body=" + r.getBody());
        JsonNode b = body(r);
        assertEquals(errorCode, b.get("errorCode").asText());
        assertEquals("LOCAL", b.get("source").asText());
        if (group == null) {
            assertTrue(b.get("group").isNull(), "group 应为 null，实际 " + b.get("group"));
        } else {
            assertEquals(group, b.get("group").asText());
        }
        assertEquals(r.getHeaders().getFirst("X-Trace-Id"), b.get("traceId").asText());
        System.out.printf("[InterfaceGroupIT] %s status=%d source=LOCAL group=%s%n",
                errorCode, httpStatus, b.get("group"));
    }

    @Test
    void groupQuotaIsIsolatedEndToEnd() {
        // orders 组自有 2/s：连续两次成功，第三次本地 429；reports 组不受影响
        ResponseEntity<String> o1 = exchange("/gateway/api/orders/1", "POST", "caller-a", "v1.0");
        ResponseEntity<String> o2 = exchange("/gateway/api/orders/2", "POST", "caller-a", "v1.0");
        ResponseEntity<String> o3 = exchange("/gateway/api/orders/3", "POST", "caller-a", "v1.0");
        assertEquals(200, o1.getStatusCode().value());
        assertEquals(200, o2.getStatusCode().value());
        assertEquals(429, o3.getStatusCode().value());

        ResponseEntity<String> report = exchange("/gateway/api/reports/daily", "POST", "caller-a", "v1.0");
        assertEquals(200, report.getStatusCode().value(), "reports 组与 orders 组配额独立");

        // 翻窗后 orders 恢复
        clock.jumpToFreshWindow(1_000);
        ResponseEntity<String> recovered = exchange("/gateway/api/orders/9", "POST", "caller-a", "v1.0");
        assertEquals(200, recovered.getStatusCode().value());
    }

    @Test
    void pausedAndNotOpenAreRejectedLocally() throws Exception {
        ResponseEntity<String> paused = exchange("/gateway/api/legacy/query", "POST", "caller-a", "v1.0");
        ResponseEntity<String> notOpen = exchange("/gateway/api/future/query", "POST", "caller-a", "v1.1");
        assertLocalError(paused, 503, "GATEWAY_INTERFACE_PAUSED", "legacy");
        assertLocalError(notOpen, 404, "GATEWAY_INTERFACE_NOT_OPEN", "future");
    }

    @Test
    void grayGroup_allowsOnlyWhitelistedCallerAndOpenVersion() throws Exception {
        ResponseEntity<String> allowed = exchange("/gateway/api/beta/query", "POST", "caller-a", "v1.1");
        assertEquals(200, allowed.getStatusCode().value(), allowed.getBody());

        ResponseEntity<String> wrongCaller = exchange("/gateway/api/beta/query", "POST", "caller-b", "v1.1");
        assertLocalError(wrongCaller, 403, "GATEWAY_INTERFACE_GRAY_NOT_ALLOWED", "beta");

        ResponseEntity<String> wrongVersion = exchange("/gateway/api/beta/query", "POST", "caller-a", "v1.0");
        assertLocalError(wrongVersion, 400, "GATEWAY_INTERFACE_VERSION_NOT_OPEN", "beta");
    }

    @Test
    void groupVersionRulesDoNotAffectOtherGroups() {
        // v1.0 对 beta 不开放，但 orders/reports 正常
        ResponseEntity<String> betaV10 = exchange("/gateway/api/beta/query", "POST", "caller-a", "v1.0");
        assertEquals(400, betaV10.getStatusCode().value());
        assertEquals(200, exchange("/gateway/api/orders/1", "POST", "caller-a", "v1.0")
                .getStatusCode().value());
        assertEquals(200, exchange("/gateway/api/reports/daily", "POST", "caller-a", "v1.0")
                .getStatusCode().value());
    }

    @Test
    void unknownRouteAndWrongMethodAreLocalErrors() throws Exception {
        ResponseEntity<String> notFound = exchange("/gateway/no/such/route", "POST", "caller-a", "v1.0");
        assertLocalError(notFound, 404, "GATEWAY_ROUTE_NOT_FOUND", null);

        ResponseEntity<String> methodNotAllowed =
                exchange("/gateway/api/orders/1", "GET", "caller-a", "v1.0");
        assertLocalError(methodNotAllowed, 405, "GATEWAY_METHOD_NOT_ALLOWED", "orders");
    }

    @Test
    void localRateLimitedCarriesRetryAfterButDownstreamDoesNot() throws Exception {
        // orders 组 2/s 打满
        exchange("/gateway/api/orders/1", "POST", "caller-a", "v1.1");
        exchange("/gateway/api/orders/2", "POST", "caller-a", "v1.1");
        ResponseEntity<String> limited = exchange("/gateway/api/orders/3", "POST", "caller-a", "v1.1");
        assertEquals(429, limited.getStatusCode().value());
        JsonNode lb = body(limited);
        assertEquals("GATEWAY_RATE_LIMITED", lb.get("errorCode").asText());
        assertEquals("LOCAL", lb.get("source").asText());
        assertTrue(limited.getHeaders().containsKey("Retry-After"));

        // 下游 429：同 HTTP 429，但 errorCode/source 不同，且不带 Retry-After
        ResponseEntity<String> downstreamBusy =
                exchange("/gateway/mock/downstream/busy", "POST", "caller-a", "v1.1");
        assertEquals(429, downstreamBusy.getStatusCode().value());
        JsonNode db = body(downstreamBusy);
        assertEquals("GATEWAY_DOWNSTREAM_RATE_LIMITED", db.get("errorCode").asText());
        assertEquals("DOWNSTREAM", db.get("source").asText());
        assertFalse(downstreamBusy.getHeaders().containsKey("Retry-After"));
    }
}
