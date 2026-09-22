package com.github.h3yow.apigatewayjava.gateway.web;

import com.github.h3yow.apigatewayjava.gateway.core.GatewayException;
import com.github.h3yow.apigatewayjava.gateway.core.GatewayOrchestrator;
import com.github.h3yow.apigatewayjava.gateway.model.ApiVersion;
import com.github.h3yow.apigatewayjava.gateway.model.GatewayRequest;
import com.github.h3yow.apigatewayjava.gateway.model.GatewayResponse;
import com.github.h3yow.apigatewayjava.gateway.model.RejectReason;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 网关统一入口：{@code /gateway/**}。
 * 路径 /gateway/foo/bar 会被转发到下游的 /foo/bar。
 *
 * 入口层只做“请求整形”：解析调用方与版本头（无法解析的版本在此被明确拒绝），
 * 所有策略判定都收敛在 orchestrator，保证各入口结论一致。
 */
@RestController
@RequestMapping("/gateway")
public class GatewayController {

    public static final String CALLER_HEADER = "X-Caller-Id";
    public static final String VERSION_HEADER = "X-Api-Version";

    private final GatewayOrchestrator orchestrator;

    public GatewayController(GatewayOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    @RequestMapping("/**")
    public ResponseEntity<String> forward(HttpServletRequest servletRequest,
                                          @RequestBody(required = false) String body) {
        String traceId = (String) servletRequest.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE);

        String caller = servletRequest.getHeader(CALLER_HEADER);
        if (caller == null || caller.isBlank()) {
            // 未携带调用方身份：明确拒绝（区别于“已携带但未登记→默认策略”）
            throw new GatewayException(RejectReason.MISSING_CALLER, null, null, 0L,
                    "缺少请求头 " + CALLER_HEADER + "，无法识别调用方");
        }

        String rawVersion = servletRequest.getHeader(VERSION_HEADER);
        ApiVersion declared = null;
        if (rawVersion != null && !rawVersion.isBlank()) {
            try {
                declared = ApiVersion.parse(rawVersion);
            } catch (IllegalArgumentException e) {
                throw new GatewayException(RejectReason.UNKNOWN_VERSION, caller, null, 0L,
                        "请求头 " + VERSION_HEADER + " 无法识别: " + rawVersion + "；拒绝且不回落默认版本");
            }
        }

        String downstreamPath = servletRequest.getRequestURI().substring("/gateway".length());
        GatewayRequest gatewayRequest = new GatewayRequest(
                traceId,
                caller.trim(),
                declared,
                servletRequest.getMethod(),
                downstreamPath,
                body);

        GatewayResponse response = orchestrator.handle(gatewayRequest);
        return ResponseEntity.status(response.status())
                .contentType(MediaType.APPLICATION_JSON)
                .body(response.body());
    }
}
