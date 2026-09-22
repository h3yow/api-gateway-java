package com.github.h3yow.apigatewayjava.gateway.downstream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 本地内置的模拟下游服务（路径前缀 /mock-downstream），仅用于本地端到端验证：
 * <ul>
 *   <li>{@code scenario=ok}（默认）：返回 200 与 JSON 体；</li>
 *   <li>{@code scenario=timeout}：休眠 5 秒，触发网关读超时；</li>
 *   <li>{@code scenario=error}：返回 500；</li>
 *   <li>{@code scenario=throttle}：返回 429 并携带 Retry-After: 2 秒。</li>
 * </ul>
 * 回显 X-Trace-Id 以便人工核对追踪标识贯穿情况。
 */
@RestController
@RequestMapping("/mock-downstream")
public class MockDownstreamController {

    private static final Logger log = LoggerFactory.getLogger(MockDownstreamController.class);

    @GetMapping("/items")
    public ResponseEntity<Map<String, Object>> items(@RequestParam(defaultValue = "ok") String scenario,
                                                     @RequestHeader(value = "X-Trace-Id", required = false)
                                                     String traceId) throws InterruptedException {
        log.info("模拟下游收到请求 traceId={} scenario={}", traceId, scenario);
        return switch (scenario) {
            case "ok" -> ResponseEntity.ok(Map.of(
                    "service", "mock-downstream",
                    "traceId", String.valueOf(traceId),
                    "items", java.util.List.of("a", "b")));
            case "timeout" -> {
                Thread.sleep(5_000L);
                yield ResponseEntity.status(HttpStatus.GATEWAY_TIMEOUT)
                        .body(Map.of("error", "should have timed out at gateway"));
            }
            case "error" -> ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "simulated downstream failure", "traceId", String.valueOf(traceId)));
            case "throttle" -> ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .header(HttpHeaders.RETRY_AFTER, "2")
                    .body(Map.of("error", "simulated downstream throttle", "traceId", String.valueOf(traceId)));
            default -> ResponseEntity.badRequest()
                    .body(Map.of("error", "unknown scenario: " + scenario));
        };
    }
}
