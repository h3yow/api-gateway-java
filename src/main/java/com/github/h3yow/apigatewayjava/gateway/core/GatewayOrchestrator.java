package com.github.h3yow.apigatewayjava.gateway.core;

import com.github.h3yow.apigatewayjava.gateway.downstream.DownstreamClient;
import com.github.h3yow.apigatewayjava.gateway.downstream.DownstreamException;
import com.github.h3yow.apigatewayjava.gateway.group.GroupAuthorizer;
import com.github.h3yow.apigatewayjava.gateway.group.RouteRegistry;
import com.github.h3yow.apigatewayjava.gateway.model.ApiGroupPolicy;
import com.github.h3yow.apigatewayjava.gateway.model.GatewayRequest;
import com.github.h3yow.apigatewayjava.gateway.model.GatewayResponse;
import com.github.h3yow.apigatewayjava.gateway.model.GroupAuthorization;
import com.github.h3yow.apigatewayjava.gateway.model.RateLimitDecision;
import com.github.h3yow.apigatewayjava.gateway.model.Route;
import com.github.h3yow.apigatewayjava.gateway.model.VersionNegotiation;
import com.github.h3yow.apigatewayjava.gateway.ratelimit.RateLimiter;
import com.github.h3yow.apigatewayjava.gateway.version.VersionNegotiator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 网关编排。固定链路：
 * <pre>
 *   版本协商(调用方级) → 路由解析(404/405 本地) → 接口组准入(状态/灰度/组版本)
 *     → 按组配额扣减(全链路仅此一次) → 下游调用 → 成功/异常映射
 * </pre>
 * 配额守恒要点：
 * <ul>
 *     <li>版本/路由/组准入任一失败：不进入限流，不扣减；</li>
 *     <li>限流拒绝：tryAcquire 自身不扣减；</li>
 *     <li>一旦放行，配额已原子扣减，下游超时/异常不退还（消费了调用机会）；</li>
 *     <li>本方法对 tryAcquire 只调用一次，无任何重试逻辑，故同一请求不可能重复扣减；</li>
 *     <li>调用方的“重试”是一次新的 HTTP 请求（新 traceId），会重新走完整判定。</li>
 * </ul>
 */
public class GatewayOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(GatewayOrchestrator.class);

    private final VersionNegotiator versionNegotiator;
    private final RouteRegistry routeRegistry;
    private final ApiGroupConfigSupplier groupConfig;
    private final GroupAuthorizer groupAuthorizer;
    private final RateLimiter rateLimiter;
    private final DownstreamClient downstreamClient;

    public GatewayOrchestrator(VersionNegotiator versionNegotiator,
                               RouteRegistry routeRegistry,
                               ApiGroupConfigSupplier groupConfig,
                               GroupAuthorizer groupAuthorizer,
                               RateLimiter rateLimiter,
                               DownstreamClient downstreamClient) {
        this.versionNegotiator = versionNegotiator;
        this.routeRegistry = routeRegistry;
        this.groupConfig = groupConfig;
        this.groupAuthorizer = groupAuthorizer;
        this.rateLimiter = rateLimiter;
        this.downstreamClient = downstreamClient;
    }

    /** 只暴露编排所需的组策略查找，避免直接依赖整个配置类。 */
    @FunctionalInterface
    public interface ApiGroupConfigSupplier {
        ApiGroupPolicy policyFor(String groupId);
    }

    public GatewayResponse handle(GatewayRequest request) {
        // 1) 调用方级版本协商（系统支持集合 + 调用方白名单）
        VersionNegotiation vn = versionNegotiator.negotiate(request.caller(), request.declaredVersion());
        if (!vn.allowed()) {
            logReject(request, vn, null);
            throw new GatewayException(vn.rejectReason(), request.caller(), request.declaredVersion(),
                    0L, vn.basis());
        }

        // 2) 路由解析：路径不存在 / 方法不支持都是本地错误，绝不转发下游
        RouteRegistry.Resolution routeResolution = routeRegistry.resolve(request.method(), request.path());
        Route route = routeResolution.route();
        String groupId = route == null ? null : route.groupId();
        if (!routeResolution.matched()) {
            log.info("gateway decision traceId={} caller={} version={} group={} path={} decision=REJECT "
                            + "source=LOCAL reason={} basis={}",
                    request.traceId(), request.caller(), vn.resolved().httpValue(), groupId, request.path(),
                    routeResolution.rejectReason(), routeResolution.basis());
            throw new GatewayException(routeResolution.rejectReason(), request.caller(), vn.resolved(),
                    groupId, 0L, routeResolution.basis());
        }

        // 3) 接口组准入：上线状态 / 灰度白名单 / 组开放版本
        ApiGroupPolicy groupPolicy = groupConfig.policyFor(groupId);
        GroupAuthorization ga = groupAuthorizer.authorize(request.caller(), vn.resolved(), groupPolicy);
        if (!ga.allowed()) {
            log.info("gateway decision traceId={} caller={} version={} group={} decision=REJECT "
                            + "source=LOCAL reason={} basis={}",
                    request.traceId(), request.caller(), vn.resolved().httpValue(), groupId,
                    ga.rejectReason(), ga.basis());
            throw new GatewayException(ga.rejectReason(), request.caller(), vn.resolved(), groupId,
                    0L, ga.basis());
        }

        // 4) 按“调用方 × 版本 × 接口组”配额扣减：tryAcquire 全请求只调用这一次
        RateLimitDecision rl = rateLimiter.tryAcquire(request.caller(), vn.resolved(), groupId);
        if (!rl.acquired()) {
            log.info("gateway decision traceId={} caller={} version={} group={} quota={}/{} decision=REJECT "
                            + "source=LOCAL reason={} retryAfterMillis={} basis={}",
                    request.traceId(), request.caller(), vn.resolved().httpValue(), groupId,
                    rl.used(), rl.limit(), rl.rejectReason(), rl.retryAfterMillis(), rl.basis());
            throw new GatewayException(rl.rejectReason(), request.caller(), vn.resolved(), groupId,
                    rl.retryAfterMillis(), rl.basis());
        }

        // 5) 下游调用（配额已扣减且不退还）
        log.info("gateway decision traceId={} caller={} version={} group={} quota={}/{} remaining={} "
                        + "decision=PASS basis={}",
                request.traceId(), request.caller(), vn.resolved().httpValue(), groupId,
                rl.used(), rl.limit(), rl.remaining(), rl.basis());
        try {
            GatewayResponse response = downstreamClient.invoke(request, vn.resolved());
            log.info("gateway downstream traceId={} caller={} version={} group={} status=OK "
                            + "source=DOWNSTREAM downstreamStatus={}",
                    request.traceId(), request.caller(), vn.resolved().httpValue(), groupId,
                    response.status());
            return response;
        } catch (DownstreamException e) {
            log.info("gateway decision traceId={} caller={} version={} group={} quota={}/{} "
                            + "used-up-not-refunded decision=REJECT source=DOWNSTREAM reason={} message={}",
                    request.traceId(), request.caller(), vn.resolved().httpValue(), groupId,
                    rl.used(), rl.limit(), e.reason(), e.getMessage());
            throw new GatewayException(e.reason(), request.caller(), vn.resolved(), groupId,
                    0L, e.getMessage(), e);
        }
    }

    private void logReject(GatewayRequest request, VersionNegotiation vn, String groupId) {
        log.info("gateway decision traceId={} caller={} version={} group={} quota=not-checked decision=REJECT "
                        + "source=LOCAL reason={} basis={}",
                request.traceId(), request.caller(), display(request), groupId,
                vn.rejectReason(), vn.basis());
    }

    private static String display(GatewayRequest request) {
        return request.declaredVersion() == null ? "none" : request.declaredVersion().httpValue();
    }
}
