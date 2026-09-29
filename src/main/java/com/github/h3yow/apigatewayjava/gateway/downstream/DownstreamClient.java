package com.github.h3yow.apigatewayjava.gateway.downstream;

import com.github.h3yow.apigatewayjava.gateway.model.ApiVersion;
import com.github.h3yow.apigatewayjava.gateway.model.GatewayRequest;
import com.github.h3yow.apigatewayjava.gateway.model.GatewayResponse;
import com.github.h3yow.apigatewayjava.gateway.model.RejectReason;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.context.WebServerInitializedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.concurrent.atomic.AtomicReference;

/**
 * 下游 HTTP 客户端。
 *
 * <p>本地部署时下游即同进程的 mock 服务。由于 RANDOM_PORT 场景下实际端口在 WebServer 初始化
 * 事件中才确定，这里监听 {@link WebServerInitializedEvent}，在端口就绪后把
 * {@code http://localhost:${server.port}} 占位基址解析为真实端口并惰性构建 RestClient。
 *
 * <p>三类下游问题映射为可区分的 {@link DownstreamException}：
 * <ul>
 *     <li>读取/连接超时、IO 故障 → DOWNSTREAM_TIMEOUT；</li>
 *     <li>下游 429 → DOWNSTREAM_RATE_LIMITED；</li>
 *     <li>其他非 2xx → DOWNSTREAM_ERROR。</li>
 * </ul>
 */
public class DownstreamClient {

    private final String configuredBaseUrl;
    private final long timeoutMillis;
    private final AtomicReference<RestClient> restClient = new AtomicReference<>();
    private volatile String resolvedBaseUrl;

    public DownstreamClient(@Value("${gateway.downstream.base-url}") String configuredBaseUrl,
                            @Value("${gateway.downstream.timeout-millis:300}") long timeoutMillis) {
        this.configuredBaseUrl = configuredBaseUrl;
        this.timeoutMillis = timeoutMillis;
        this.resolvedBaseUrl = configuredBaseUrl;
        // 基址含动态端口占位（:${server.port} 或 :0）时惰性构建，待端口就绪事件解析真实端口；
        // 其余基址（纯单测对真实外部 HTTP 服务器）立即构建
        if (!configuredBaseUrl.contains("${server.port}") && !configuredBaseUrl.endsWith(":0")) {
            SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
            factory.setConnectTimeout((int) Math.min(timeoutMillis, Integer.MAX_VALUE));
            factory.setReadTimeout((int) Math.min(timeoutMillis, Integer.MAX_VALUE));
            this.restClient.set(RestClient.builder()
                    .baseUrl(configuredBaseUrl).requestFactory(factory).build());
        }
    }

    @EventListener(WebServerInitializedEvent.class)
    public void onWebServerReady(WebServerInitializedEvent event) {
        int actualPort = event.getWebServer().getPort();
        String resolved = configuredBaseUrl.replace(":${server.port}", ":" + actualPort)
                .replace(":0", ":" + actualPort);
        this.resolvedBaseUrl = resolved;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) Math.min(timeoutMillis, Integer.MAX_VALUE));
        factory.setReadTimeout((int) Math.min(timeoutMillis, Integer.MAX_VALUE));
        this.restClient.set(RestClient.builder().baseUrl(resolved).requestFactory(factory).build());
    }

    private RestClient client() {
        RestClient c = restClient.get();
        if (c != null) {
            return c;
        }
        // 非 Web 环境（纯单测不会走到这里）下的兜底构建
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) Math.min(timeoutMillis, Integer.MAX_VALUE));
        factory.setReadTimeout((int) Math.min(timeoutMillis, Integer.MAX_VALUE));
        RestClient created = RestClient.builder()
                .baseUrl(configuredBaseUrl).requestFactory(factory).build();
        restClient.compareAndSet(null, created);
        return restClient.get();
    }

    public GatewayResponse invoke(GatewayRequest request, ApiVersion routedVersion) {
        try {
            var response = client()
                    .method(HttpMethod.valueOf(request.method()))
                    .uri(request.path())
                    .header("X-Trace-Id", request.traceId())
                    .header("X-Api-Version", routedVersion.httpValue())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request.body() == null ? "" : request.body())
                    .retrieve()
                    .onStatus(s -> s.value() == 429, (req, res) -> {
                        throw new DownstreamException(RejectReason.DOWNSTREAM_RATE_LIMITED,
                                "下游限流 status=429 traceId=" + request.traceId()
                                        + " url=" + resolvedBaseUrl + request.path());
                    })
                    .onStatus(s -> !s.is2xxSuccessful(), (req, res) -> {
                        throw new DownstreamException(RejectReason.DOWNSTREAM_ERROR,
                                "下游返回非成功状态 status=" + res.getStatusCode()
                                        + " traceId=" + request.traceId()
                                        + " url=" + resolvedBaseUrl + request.path());
                    })
                    .toEntity(String.class);
            return new GatewayResponse(response.getStatusCode().value(), response.getBody());
        } catch (ResourceAccessException e) {
            throw new DownstreamException(RejectReason.DOWNSTREAM_TIMEOUT,
                    "下游调用超时/IO故障 traceId=" + request.traceId()
                            + " url=" + resolvedBaseUrl + request.path() + " : " + rootMessage(e), e);
        } catch (RestClientException e) {
            if (isIOTimeout(e)) {
                throw new DownstreamException(RejectReason.DOWNSTREAM_TIMEOUT,
                        "下游调用超时/IO故障 traceId=" + request.traceId()
                                + " url=" + resolvedBaseUrl + request.path() + " : " + rootMessage(e), e);
            }
            throw new DownstreamException(RejectReason.DOWNSTREAM_ERROR,
                    "下游调用失败 traceId=" + request.traceId()
                            + " url=" + resolvedBaseUrl + request.path() + " : " + rootMessage(e), e);
        }
    }

    private static boolean isIOTimeout(Throwable t) {
        Throwable cur = t;
        while (cur != null) {
            String name = cur.getClass().getSimpleName();
            if (name.contains("Timeout") || name.contains("TimedOut")) {
                return true;
            }
            cur = cur.getCause();
        }
        return false;
    }

    private static String rootMessage(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        return cur.getClass().getSimpleName() + ": " + cur.getMessage();
    }
}
