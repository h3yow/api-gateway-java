package com.github.h3yow.apigatewayjava.gateway.core;

import com.github.h3yow.apigatewayjava.gateway.config.CallerPolicy;
import com.github.h3yow.apigatewayjava.gateway.config.GatewayProperties;
import com.github.h3yow.apigatewayjava.gateway.downstream.DownstreamException;
import com.github.h3yow.apigatewayjava.gateway.downstream.DownstreamFailure;
import com.github.h3yow.apigatewayjava.gateway.downstream.DownstreamInvoker;
import com.github.h3yow.apigatewayjava.gateway.downstream.DownstreamResponse;
import com.github.h3yow.apigatewayjava.gateway.error.ApiError;
import com.github.h3yow.apigatewayjava.gateway.error.GatewayErrorCode;
import com.github.h3yow.apigatewayjava.gateway.ratelimit.QuotaKey;
import com.github.h3yow.apigatewayjava.gateway.ratelimit.RateLimitDecision;
import com.github.h3yow.apigatewayjava.gateway.ratelimit.RateLimiter;
import com.github.h3yow.apigatewayjava.gateway.ratelimit.RateLimitReason;
import com.github.h3yow.apigatewayjava.gateway.version.VersionDecision;
import com.github.h3yow.apigatewayjava.gateway.version.VersionNegotiator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;

/**
 * 网关编排核心，所有入口（/gateway/**）共用同一处理链路：
 *
 * <ol>
 *   <li>解析调用方策略（未登记调用方回落 "default" 策略，不无限放行）；</li>
 *   <li>版本协商（缺失/未知/不兼容一律拒绝，不回落默认版本）；</li>
 *   <li>按 调用方+版本 维度做一次本地固定窗口配额判定 —— 本方法是全链路唯一扣减点，
 *       下游调用不再二次计数；</li>
 *   <li>调用下游，把超时/I-O 异常/429/5xx 统一映射为可区分的 {@link GatewayErrorCode}；</li>
 *   <li>每个结论都带 traceId，调用方/版本/配额/判定依据均写入日志。</li>
 * </ol>
 *
 * 重试请求每次携带新的 traceId 重新进入本方法并重新判定，不做幂等缓存。
 */
@Service
public class GatewayService {

    private static final Logger log = LoggerFactory.getLogger(GatewayService.class);

    private final GatewayProperties properties;
    private final VersionNegotiator versionNegotiator;
    private final RateLimiter rateLimiter;
    private final DownstreamInvoker downstreamInvoker;
    private final Clock clock;

    public GatewayService(GatewayProperties properties,
                          VersionNegotiator versionNegotiator,
                          RateLimiter rateLimiter,
                          DownstreamInvoker downstreamInvoker,
                          Clock clock) {
        this.properties = properties;
        this.versionNegotiator = versionNegotiator;
        this.rateLimiter = rateLimiter;
        this.downstreamInvoker = downstreamInvoker;
        this.clock = clock;
    }

    public record GatewayResult(int httpStatus, String body, ApiError error) {
        public static GatewayResult ok(int httpStatus, String body) {
            return new GatewayResult(httpStatus, body, null);
        }

        public static GatewayResult failure(ApiError error) {
            return new GatewayResult(GatewayErrorCode.valueOf(error.errorCode()).httpStatus(), null, error);
        }
    }

    public GatewayResult handle(String callerId, String rawVersion, String path, String traceId) {
        // 1. 策略解析
        GatewayProperties.ResolvedPolicy resolved = properties.resolve(callerId);
        CallerPolicy policy = resolved.policy();
        log.info("网关判定 traceId={} 调用方={} 策略来源={} 配额={} 窗口={}ms 授权版本={}",
                traceId, callerId, resolved.fallback() ? "default" : "explicit",
                policy.quota(), policy.windowMillis(), policy.supportedVersions());

        // 2. 版本协商
        VersionDecision versionDecision = versionNegotiator.negotiate(rawVersion, policy);
        if (!versionDecision.accepted()) {
            GatewayErrorCode code = switch (versionDecision.reason()) {
                case "VERSION_MISSING" -> GatewayErrorCode.VERSION_MISSING;
                case "VERSION_UNKNOWN" -> GatewayErrorCode.VERSION_UNKNOWN;
                case "VERSION_NOT_SUPPORTED" -> GatewayErrorCode.VERSION_NOT_SUPPORTED;
                default -> GatewayErrorCode.VERSION_UNKNOWN;
            };
            // 版本未通过：不扣减任何配额。
            log.info("网关结论 traceId={} 调用方={} 版本={} 判定依据=版本协商拒绝({})",
                    traceId, callerId, rawVersion, code);
            return GatewayResult.failure(new ApiError(
                    code.name(), code.name().toLowerCase().replace('_', '-'),
                    callerId, rawVersion, versionDecision.reason(), null, traceId));
        }

        // 3. 配额判定（全链路唯一一次扣减）
        QuotaKey quotaKey = new QuotaKey(callerId, versionDecision.version());
        RateLimitDecision rateDecision =
                rateLimiter.tryAcquire(quotaKey, policy.quota(), policy.windowMillis(), clock);
        if (!rateDecision.allowed()) {
            // 当前本地限流只可能是本地配额耗尽；下游 429 走下游分类，不在此产生。
            GatewayErrorCode code = rateDecision.reason() == RateLimitReason.DOWNSTREAM_THROTTLED
                    ? GatewayErrorCode.DOWNSTREAM_RATE_LIMITED
                    : GatewayErrorCode.LOCAL_RATE_LIMITED;
            log.info("网关结论 traceId={} 调用方={} 版本={} 配额={} 判定依据=本地配额耗尽 剩余={} 建议重试={}ms",
                    traceId, callerId, versionDecision.version(), policy.quota(),
                    rateDecision.remaining(), rateDecision.retryAfterMillis());
            return GatewayResult.failure(new ApiError(
                    code.name(), "local quota exhausted in fixed window",
                    callerId, versionDecision.version().name(),
                    RateLimitReason.LOCAL_QUOTA_EXCEEDED.name(),
                    rateDecision.retryAfterMillis(), traceId));
        }

        // 4. 下游调用（通过本地配额后执行；失败也不回补配额——配额代表已接纳的请求，
        //    与下游结果无关；重试是一次新请求，重新判定）
        try {
            DownstreamResponse response = downstreamInvoker.invoke(path, traceId);
            log.info("网关结论 traceId={} 调用方={} 版本={} 配额={} 剩余={} 判定依据=放行成功 下游状态={}",
                    traceId, callerId, versionDecision.version(), policy.quota(),
                    rateDecision.remaining(), response.status());
            return GatewayResult.ok(response.status(), response.body());
        } catch (DownstreamException ex) {
            GatewayErrorCode code = mapFailure(ex.failure());
            Long retryAfter = ex.failure() == DownstreamFailure.THROTTLED
                    ? (ex.retryAfterMillis() != null ? ex.retryAfterMillis() : 2000L)
                    : null;
            log.warn("网关结论 traceId={} 调用方={} 版本={} 配额={} 判定依据=下游失败({}) 下游状态={} 建议重试={}ms",
                    traceId, callerId, versionDecision.version(), policy.quota(),
                    code, ex.downstreamStatus(), retryAfter);
            return GatewayResult.failure(new ApiError(
                    code.name(), ex.getMessage(),
                    callerId, versionDecision.version().name(),
                    ex.failure().name(), retryAfter, traceId));
        } catch (RuntimeException ex) {
            log.error("网关结论 traceId={} 调用方={} 版本={} 判定依据=未预期异常",
                    traceId, callerId, versionDecision.version(), ex);
            return GatewayResult.failure(new ApiError(
                    GatewayErrorCode.INTERNAL_ERROR.name(), "unexpected gateway error",
                    callerId, versionDecision.version().name(),
                    DownstreamFailure.IO_ERROR.name(), null, traceId));
        }
    }

    private static GatewayErrorCode mapFailure(DownstreamFailure failure) {
        return switch (failure) {
            case TIMEOUT -> GatewayErrorCode.DOWNSTREAM_TIMEOUT;
            case THROTTLED -> GatewayErrorCode.DOWNSTREAM_RATE_LIMITED;
            case SERVER_ERROR -> GatewayErrorCode.DOWNSTREAM_ERROR;
            case IO_ERROR -> GatewayErrorCode.DOWNSTREAM_UNAVAILABLE;
        };
    }
}
