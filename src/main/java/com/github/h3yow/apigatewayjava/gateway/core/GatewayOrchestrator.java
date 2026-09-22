package com.github.h3yow.apigatewayjava.gateway.core;

import com.github.h3yow.apigatewayjava.gateway.downstream.DownstreamClient;
import com.github.h3yow.apigatewayjava.gateway.downstream.DownstreamException;
import com.github.h3yow.apigatewayjava.gateway.model.GatewayRequest;
import com.github.h3yow.apigatewayjava.gateway.model.GatewayResponse;
import com.github.h3yow.apigatewayjava.gateway.model.RateLimitDecision;
import com.github.h3yow.apigatewayjava.gateway.model.VersionNegotiation;
import com.github.h3yow.apigatewayjava.gateway.ratelimit.RateLimiter;
import com.github.h3yow.apigatewayjava.gateway.version.VersionNegotiator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 网关编排。固定链路：
 * <pre>
 *   版本协商 → 限流扣减（全链路仅此处一次）→ 下游调用 → 成功/异常映射
 * </pre>
 * 配额守恒要点：
 * <ul>
 *     <li>协商失败：不进入限流，不扣减；</li>
 *     <li>限流拒绝：tryAcquire 自身不扣减；</li>
 *     <li>一旦放行，配额已原子扣减，下游超时/异常不退还（消费了调用机会）；</li>
 *     <li>本方法对 tryAcquire 只调用一次，无任何重试逻辑，故同一请求不可能重复扣减；</li>
 *     <li>调用方的“重试”是一次新的 HTTP 请求（新 traceId），会重新走完整判定。</li>
 * </ul>
 */
public class GatewayOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(GatewayOrchestrator.class);

    private final VersionNegotiator versionNegotiator;
    private final RateLimiter rateLimiter;
    private final DownstreamClient downstreamClient;

    public GatewayOrchestrator(VersionNegotiator versionNegotiator,
                               RateLimiter rateLimiter,
                               DownstreamClient downstreamClient) {
        this.versionNegotiator = versionNegotiator;
        this.rateLimiter = rateLimiter;
        this.downstreamClient = downstreamClient;
    }

    public GatewayResponse handle(GatewayRequest request) {
        // 1) 版本协商
        VersionNegotiation vn = versionNegotiator.negotiate(request.caller(), request.declaredVersion());
        if (!vn.allowed()) {
            log.info("gateway decision traceId={} caller={} version={} quota=not-checked decision=REJECT "
                            + "reason={} basis={}",
                    request.traceId(), request.caller(), display(request), vn.rejectReason(), vn.basis());
            throw new GatewayException(vn.rejectReason(), request.caller(), request.declaredVersion(),
                    0L, vn.basis());
        }

        // 2) 限流：tryAcquire 全请求只调用这一次
        RateLimitDecision rl = rateLimiter.tryAcquire(request.caller(), vn.resolved());
        if (!rl.acquired()) {
            log.info("gateway decision traceId={} caller={} version={} quota={}/{} used={} decision=REJECT "
                            + "reason={} retryAfterMillis={} basis={}",
                    request.traceId(), request.caller(), vn.resolved().httpValue(),
                    rl.used(), rl.limit(), rl.used(),
                    rl.rejectReason(), rl.retryAfterMillis(), rl.basis());
            throw new GatewayException(rl.rejectReason(), request.caller(), vn.resolved(),
                    rl.retryAfterMillis(), rl.basis());
        }

        // 3) 下游调用（配额已扣减且不退还）
        log.info("gateway decision traceId={} caller={} version={} quota={}/{} remaining={} "
                        + "decision=PASS basis={}",
                request.traceId(), request.caller(), vn.resolved().httpValue(),
                rl.used(), rl.limit(), rl.remaining(), rl.basis());
        try {
            GatewayResponse response = downstreamClient.invoke(request, vn.resolved());
            log.info("gateway downstream traceId={} caller={} version={} status=OK downstreamStatus={}",
                    request.traceId(), request.caller(), vn.resolved().httpValue(), response.status());
            return response;
        } catch (DownstreamException e) {
            log.info("gateway decision traceId={} caller={} version={} quota={}/{} used-up-not-refunded "
                            + "decision=REJECT reason={} message={}",
                    request.traceId(), request.caller(), vn.resolved().httpValue(),
                    rl.used(), rl.limit(), e.reason(), e.getMessage());
            throw new GatewayException(e.reason(), request.caller(), vn.resolved(), 0L, e.getMessage(), e);
        }
    }

    private static String display(GatewayRequest request) {
        return request.declaredVersion() == null ? "none" : request.declaredVersion().httpValue();
    }
}
