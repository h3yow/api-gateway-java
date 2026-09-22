package com.github.h3yow.apigatewayjava.gateway.downstream;

import com.github.h3yow.apigatewayjava.gateway.config.GatewayProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.InputStream;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.concurrent.TimeoutException;

/**
 * 基于 {@link RestClient} 的下游调用实现。
 *
 * <p>把所有下游结果归并为两类：2xx 直接返回；其余抛出携带 {@link DownstreamFailure}
 * 分类的 {@link DownstreamException}（429 → THROTTLED，5xx → SERVER_ERROR，
 * 读/连接超时 → TIMEOUT，其他 I/O 问题 → IO_ERROR），交由网关统一映射。
 * 不在 4xx/5xx 时抛默认 ResponseErrorException，而是统一读取响应体做分类。
 */
@Component
public class HttpDownstreamInvoker implements DownstreamInvoker {

    private static final Logger log = LoggerFactory.getLogger(HttpDownstreamInvoker.class);

    private final RestClient restClient;

    public HttpDownstreamInvoker(GatewayProperties properties) {
        GatewayProperties.DownstreamConfig config = properties.getDownstream();
        this.restClient = RestClient.builder()
                .baseUrl(config.getBaseUrl())
                .requestFactory(requestFactory(config.getTimeoutMillis()))
                .build();
    }

    private static ClientHttpRequestFactory requestFactory(long timeoutMillis) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(timeoutMillis));
        factory.setReadTimeout(Duration.ofMillis(timeoutMillis));
        return factory;
    }

    @Override
    public DownstreamResponse invoke(String path, String traceId) {
        try {
            return restClient.get()
                    .uri(path)
                    .header("X-Trace-Id", traceId)
                    .exchange((request, response) -> mapResponse(response, traceId), false);
        } catch (ResourceAccessException ex) {
            DownstreamFailure failure = isTimeout(ex) ? DownstreamFailure.TIMEOUT : DownstreamFailure.IO_ERROR;
            log.warn("下游调用失败 traceId={} path={} 分类={} message={}",
                    traceId, path, failure, ex.getMessage());
            throw new DownstreamException(failure, 0, null,
                    "downstream access failure: " + failure, ex);
        }
    }

    private DownstreamResponse mapResponse(ClientHttpResponse response, String traceId) throws IOException {
        int status = response.getStatusCode().value();
        String body = readBody(response);
        if (response.getStatusCode().is2xxSuccessful()) {
            log.info("下游调用成功 traceId={} status={}", traceId, status);
            return new DownstreamResponse(status, body);
        }

        if (status == HttpStatus.TOO_MANY_REQUESTS.value()) {
            Long retryAfterMillis = parseRetryAfter(response.getHeaders());
            log.warn("下游限流 traceId={} status=429 retryAfterMillis={}", traceId, retryAfterMillis);
            throw new DownstreamException(DownstreamFailure.THROTTLED, status, retryAfterMillis,
                    "downstream rate limited (429)", null);
        }

        if (response.getStatusCode().is5xxServerError()) {
            log.warn("下游服务错误 traceId={} status={}", traceId, status);
            throw new DownstreamException(DownstreamFailure.SERVER_ERROR, status, null,
                    "downstream server error status=" + status, null);
        }

        log.warn("下游拒绝 traceId={} status={}", traceId, status);
        throw new DownstreamException(DownstreamFailure.IO_ERROR, status, null,
                "downstream non-success status=" + status, null);
    }

    private static String readBody(ClientHttpResponse response) throws IOException {
        try (InputStream in = response.getBody()) {
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    private static Long parseRetryAfter(HttpHeaders headers) {
        String header = headers.getFirst(HttpHeaders.RETRY_AFTER);
        if (header == null || header.isBlank()) {
            return null;
        }
        try {
            // 优先按 delta-seconds 解析；HTTP 日期格式这里不展开，缺失则不给建议值。
            return Duration.ofSeconds(Long.parseLong(header.trim())).toMillis();
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static boolean isTimeout(Throwable throwable) {
        for (Throwable t = throwable; t != null; t = t.getCause()) {
            if (t instanceof SocketTimeoutException || t instanceof TimeoutException) {
                return true;
            }
        }
        return false;
    }
}
