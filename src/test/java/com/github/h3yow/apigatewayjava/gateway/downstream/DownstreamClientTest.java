package com.github.h3yow.apigatewayjava.gateway.downstream;

import com.github.h3yow.apigatewayjava.gateway.model.ApiVersion;
import com.github.h3yow.apigatewayjava.gateway.model.GatewayRequest;
import com.github.h3yow.apigatewayjava.gateway.model.GatewayResponse;
import com.github.h3yow.apigatewayjava.gateway.model.RejectReason;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 下游客户端单测：用真实本地 HTTP 服务器（MockWebServer）覆盖
 * 正常、下游 429、下游 500、下游超时，断言错误原因可区分。
 */
class DownstreamClientTest {

    private MockWebServer server;
    private DownstreamClient client;

    private static GatewayRequest request(String traceId) {
        return new GatewayRequest(traceId, "caller-a", new ApiVersion(1, 0),
                "POST", "/mock/downstream/echo", "{}");
    }

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start(0);
        client = new DownstreamClient("http://localhost:" + server.getPort(), 300);
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    @Test
    void successReturnsBodyAndForwardsTraceHeaders() throws InterruptedException {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody("{\"ok\":true}")
                .addHeader("Content-Type", "application/json"));

        GatewayResponse response = client.invoke(request("trace-success"), new ApiVersion(1, 0));
        assertEquals(200, response.status());
        assertTrue(response.body().contains("\"ok\":true"));

        RecordedRequest recorded = server.takeRequest();
        assertEquals("trace-success", recorded.getHeaders().get("X-Trace-Id"));
        assertEquals("v1.0", recorded.getHeaders().get("X-Api-Version"));
        System.out.println("[DownstreamTest] 正常响应 status=200 traceId=trace-success");
    }

    @Test
    void downstream429MapsToDownstreamRateLimited() {
        server.enqueue(new MockResponse().setResponseCode(429).setBody("{\"busy\":true}"));

        DownstreamException ex = assertThrows(DownstreamException.class,
                () -> client.invoke(request("trace-429"), new ApiVersion(1, 0)));
        assertEquals(RejectReason.DOWNSTREAM_RATE_LIMITED, ex.reason());
        System.out.println("[DownstreamTest] 下游429 reason=" + ex.reason()
                + " traceId=trace-429 message=" + ex.getMessage());
    }

    @Test
    void downstream500MapsToDownstreamError() {
        server.enqueue(new MockResponse().setResponseCode(500).setBody("boom"));

        DownstreamException ex = assertThrows(DownstreamException.class,
                () -> client.invoke(request("trace-500"), new ApiVersion(1, 0)));
        assertEquals(RejectReason.DOWNSTREAM_ERROR, ex.reason());
        System.out.println("[DownstreamTest] 下游500 reason=" + ex.reason()
                + " traceId=trace-500 message=" + ex.getMessage());
    }

    @Test
    void downstreamSlowMapsToTimeout() {
        // 延迟 1 秒 > 客户端 300ms 读超时
        server.enqueue(new MockResponse()
                .setHeadersDelay(1, java.util.concurrent.TimeUnit.SECONDS)
                .setBody("late"));

        long start = System.currentTimeMillis();
        DownstreamException ex = assertThrows(DownstreamException.class,
                () -> client.invoke(request("trace-timeout"), new ApiVersion(1, 0)));
        long elapsed = System.currentTimeMillis() - start;
        assertEquals(RejectReason.DOWNSTREAM_TIMEOUT, ex.reason());
        assertTrue(elapsed < 1_500, "超时应在约 300ms 触发，实际 " + elapsed + "ms");
        System.out.println("[DownstreamTest] 下游超时 reason=" + ex.reason()
                + " elapsedMillis=" + elapsed + " traceId=trace-timeout");
    }
}
