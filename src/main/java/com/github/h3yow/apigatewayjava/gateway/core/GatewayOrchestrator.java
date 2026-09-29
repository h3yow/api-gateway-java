package com.github.h3yow.apigatewayjava.gateway.core;

import com.github.h3yow.apigatewayjava.gateway.downstream.DownstreamException;
import com.github.h3yow.apigatewayjava.gateway.downstream.DownstreamInvoker;
import com.github.h3yow.apigatewayjava.gateway.model.GatewayRequest;
import com.github.h3yow.apigatewayjava.gateway.model.GatewayResponse;
import com.github.h3yow.apigatewayjava.gateway.model.GroupDecision;
import com.github.h3yow.apigatewayjava.gateway.model.RateLimitDecision;
import com.github.h3yow.apigatewayjava.gateway.model.Route;
import com.github.h3yow.apigatewayjava.gateway.model.VersionNegotiation;
import com.github.h3yow.apigatewayjava.gateway.ratelimit.RateLimiter;
import com.github.h3yow.apigatewayjava.gateway.routing.InterfaceGroupGate;
import com.github.h3yow.apigatewayjava.gateway.routing.QuotaResolver;
import com.github.h3yow.apigatewayjava.gateway.routing.RouteTable;
import com.github.h3yow.apigatewayjava.gateway.version.VersionNegotiator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 网关编排。固定链路：
 * <pre>
 *   路由解析（404 本地）
 *     → 接口组方法/状态判定（405/503/404/403 本地）
 *     → 调用方版本协商（缺版本/不支持/调用方不兼容，本地，不扣配额）
 *     → 接口组版本开放判定（版本未对该组开放，本地，不扣配额）
 *     → 配额解析 + tryAcquire（全链路仅一次；失败不扣减）
 *     → 下游调用（超时/下游限流/下游异常 = DOWNSTREAM，配额不退还）
 * </pre>
 * 配额守恒：所有本地拒绝都发生在 tryAcquire 之前；放行与扣减在同一次原子迁移内；
 * 本方法对 tryAcquire 只调用一次，无任何重试，故同一请求不可能重复计数/重复扣减。
 */
public class GatewayOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(GatewayOrchestrator.class);

    private final RouteTable routeTable;
    private final InterfaceGroupGate groupGate;
    private final VersionNegotiator versionNegotiator;
    private final QuotaResolver quotaResolver;
    private final RateLimiter rateLimiter;
    private final DownstreamInvoker downstreamClient;

    public GatewayOrchestrator(RouteTable routeTable,
                               InterfaceGroupGate groupGate,
                               VersionNegotiator versionNegotiator,
                               QuotaResolver quotaResolver,
                               RateLimiter rateLimiter,
                               DownstreamInvoker downstreamClient) {
        this.routeTable = routeTable;
        this.groupGate = groupGate;
        this.versionNegotiator = versionNegotiator;
        this.quotaResolver = quotaResolver;
        this.rateLimiter = rateLimiter;
        this.downstreamClient = downstreamClient;
    }

    public GatewayResponse handle(GatewayRequest request) {
        // 1) 路由解析：未命中接口组即本地 404（绝不转发、不伪装成下游故障）
        Route route = routeTable.resolve(request.method(), request.path(), request.declaredVersion());
        String groupId = route.group() == null ? null : route.group().id();
        if (!route.matched()) {
            log.info("gateway decision traceId={} caller={} path={} decision=REJECT reason=ROUTE_NOT_FOUND "
                            + "source=LOCAL basis=route not configured",
                    request.traceId(), request.caller(), request.path());
            throw new GatewayException(com.github.h3yow.apigatewayjava.gateway.model.RejectReason.ROUTE_NOT_FOUND,
                    request.caller(), request.declaredVersion(), null, 0L,
                    "路径 " + request.path() + " 未在网关配置任何接口组/路由，本地拒绝（不转发下游）");
        }

        // 2) 接口组方法 + 上线状态判定（暂停/未开放/灰度）
        GroupDecision access = groupGate.checkAccess(route, request.caller());
        if (!access.allowed()) {
            rejectLocal(request, groupId, access.rejectReason(), access.basis(), "not-checked");
        }

        // 3) 调用方版本协商（系统支持集合 + 调用方白名单）
        VersionNegotiation vn = versionNegotiator.negotiate(request.caller(), request.declaredVersion());
        if (!vn.allowed()) {
            rejectLocal(request, groupId, vn.rejectReason(), vn.basis(), "not-checked");
        }

        // 4) 接口组版本开放范围（按声明版本判定，不静默替换）
        GroupDecision versionGate = groupGate.checkVersion(route);
        if (!versionGate.allowed()) {
            rejectLocal(request, groupId, versionGate.rejectReason(), versionGate.basis(), "not-checked");
        }

        // 5) 配额解析（接口组自有 → 回退调用方×版本；计数始终按 caller|group|version 隔离）
        var quota = quotaResolver.resolve(request.caller(), route.group(), vn.resolved());

        // 6) tryAcquire：全请求只调用这一次
        RateLimitDecision rl = rateLimiter.tryAcquire(quota);
        if (!rl.acquired()) {
            log.info("gateway decision traceId={} caller={} group={} version={} quota={}/{} used={} "
                            + "decision=REJECT source=LOCAL reason={} retryAfterMillis={} basis={}",
                    request.traceId(), request.caller(), groupId, vn.resolved().httpValue(),
                    rl.used(), rl.limit(), rl.used(),
                    rl.rejectReason(), rl.retryAfterMillis(), rl.basis());
            throw new GatewayException(rl.rejectReason(), request.caller(), vn.resolved(), groupId,
                    rl.retryAfterMillis(), rl.basis());
        }

        // 7) 下游调用（配额已扣减且不退还）
        log.info("gateway decision traceId={} caller={} group={} version={} quota={}/{} remaining={} "
                        + "decision=PASS source=LOCAL basis={}",
                request.traceId(), request.caller(), groupId, vn.resolved().httpValue(),
                rl.used(), rl.limit(), rl.remaining(), rl.basis());
        try {
            GatewayResponse response = downstreamClient.invoke(request, vn.resolved());
            log.info("gateway downstream traceId={} caller={} group={} version={} status=OK "
                            + "source=DOWNSTREAM downstreamStatus={}",
                    request.traceId(), request.caller(), groupId, vn.resolved().httpValue(),
                    response.status());
            return response;
        } catch (DownstreamException e) {
            log.info("gateway decision traceId={} caller={} group={} version={} quota={}/{} "
                            + "used-up-not-refunded decision=REJECT source=DOWNSTREAM reason={} message={}",
                    request.traceId(), request.caller(), groupId, vn.resolved().httpValue(),
                    rl.used(), rl.limit(), e.reason(), e.getMessage());
            throw new GatewayException(e.reason(), request.caller(), vn.resolved(), groupId,
                    0L, e.getMessage(), e);
        }
    }

    private void rejectLocal(GatewayRequest request, String groupId,
                             com.github.h3yow.apigatewayjava.gateway.model.RejectReason reason,
                             String basis, String quota) {
        String version = request.declaredVersion() == null ? "none" : request.declaredVersion().httpValue();
        log.info("gateway decision traceId={} caller={} group={} version={} quota={} decision=REJECT "
                        + "source=LOCAL reason={} basis={}",
                request.traceId(), request.caller(), groupId, version, quota, reason, basis);
        throw new GatewayException(reason, request.caller(), request.declaredVersion(), groupId, 0L, basis);
    }
}
