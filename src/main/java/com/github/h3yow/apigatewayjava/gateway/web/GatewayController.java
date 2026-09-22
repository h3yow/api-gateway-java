package com.github.h3yow.apigatewayjava.gateway.web;

import com.github.h3yow.apigatewayjava.gateway.core.GatewayService;
import com.github.h3yow.apigatewayjava.gateway.error.ApiError;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * 网关入口。多个入口（/gateway/items、/gateway/echo）都只做参数提取并委托
 * {@link GatewayService#handle} 同一处理链路，从而在所有入口下得到一致且可解释的结论。
 *
 * <p>约定请求头：
 * <ul>
 *   <li>{@code X-Caller-Id}：调用方标识（缺失/未登记 → 默认策略）；</li>
 *   <li>{@code X-Api-Version}：版本声明，如 v1、v2（缺失/不可识别/不兼容 → 明确拒绝）；</li>
 *   <li>{@code X-Trace-Id}：可选，缺失时由网关生成并回传。</li>
 * </ul>
 */
@RestController
public class GatewayController {

    private final GatewayService gatewayService;

    public GatewayController(GatewayService gatewayService) {
        this.gatewayService = gatewayService;
    }

    @GetMapping("/gateway/items")
    public ResponseEntity<?> items(@RequestHeader(value = "X-Caller-Id", required = false) String callerId,
                                   @RequestHeader(value = "X-Api-Version", required = false) String apiVersion,
                                   @RequestHeader(value = "X-Trace-Id", required = false) String inboundTraceId,
                                   @RequestParam(defaultValue = "ok") String scenario) {
        String traceId = resolveTraceId(inboundTraceId);
        String downstreamPath = "/mock-downstream/items?scenario=" + scenario;
        return dispatch(callerId, apiVersion, traceId, downstreamPath);
    }

    @GetMapping("/gateway/echo")
    public ResponseEntity<?> echo(@RequestHeader(value = "X-Caller-Id", required = false) String callerId,
                                  @RequestHeader(value = "X-Api-Version", required = false) String apiVersion,
                                  @RequestHeader(value = "X-Trace-Id", required = false) String inboundTraceId,
                                  @RequestParam(defaultValue = "ok") String scenario) {
        String traceId = resolveTraceId(inboundTraceId);
        // echo 入口复用同一下游与同一判定链路，仅入口不同，用于验证跨入口一致性。
        String downstreamPath = "/mock-downstream/items?scenario=" + scenario;
        return dispatch(callerId, apiVersion, traceId, downstreamPath);
    }

    private ResponseEntity<?> dispatch(String callerId, String apiVersion, String traceId, String path) {
        GatewayService.GatewayResult result = gatewayService.handle(callerId, apiVersion, path, traceId);
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Trace-Id", traceId);
        if (result.error() != null) {
            ApiError error = result.error();
            if (error.retryAfterMillis() != null) {
                // 秒级 Retry-After，向上取整，保证客户端等待足够久。
                long retrySeconds = Math.max(1L, (error.retryAfterMillis() + 999L) / 1000L);
                headers.set(HttpHeaders.RETRY_AFTER, String.valueOf(retrySeconds));
                headers.set("X-Retry-After-Millis", String.valueOf(error.retryAfterMillis()));
            }
            return ResponseEntity.status(result.httpStatus())
                    .headers(headers)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(error);
        }
        return ResponseEntity.status(result.httpStatus())
                .headers(headers)
                .contentType(MediaType.APPLICATION_JSON)
                .body(result.body());
    }

    private static String resolveTraceId(String inbound) {
        if (inbound != null && !inbound.isBlank()) {
            return inbound.trim();
        }
        return "gw-" + UUID.randomUUID();
    }
}
