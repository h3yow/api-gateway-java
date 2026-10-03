package com.github.h3yow.apigatewayjava.gateway.config;

import com.github.h3yow.apigatewayjava.gateway.core.GatewayOrchestrator;
import com.github.h3yow.apigatewayjava.gateway.downstream.DownstreamClient;
import com.github.h3yow.apigatewayjava.gateway.group.ApiGroupConfig;
import com.github.h3yow.apigatewayjava.gateway.group.GroupAuthorizer;
import com.github.h3yow.apigatewayjava.gateway.group.GroupQuotaResolver;
import com.github.h3yow.apigatewayjava.gateway.group.RouteRegistry;
import com.github.h3yow.apigatewayjava.gateway.ratelimit.RateLimitConfig;
import com.github.h3yow.apigatewayjava.gateway.ratelimit.RateLimiter;
import com.github.h3yow.apigatewayjava.gateway.version.VersionNegotiator;
import com.github.h3yow.apigatewayjava.gateway.web.TraceIdFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.util.Map;

/**
 * 网关本地装配。
 * 默认策略（可由测试替换 Bean）：
 * <pre>
 *   配额：caller-a v1.0=5/s、v1.1=10/s；caller-b 任意版本=3/s；未登记调用方=1/s；
 *         接口组可在上述之上声明独立限额/窗口（如 orders=2/s），未声明则回退到该策略；
 *   版本：系统支持 v1.0、v1.1；caller-a 两者皆可；caller-b 仅 v1.0；默认仅 v1.0；
 *   接口组：default(OPEN) / orders(OPEN,仅v1.0,2/s) / payments(OPEN,4/s) /
 *           reports(PAUSED) / beta-ai(CANARY,仅v1.1,caller-a) / ledger-v2(NOT_OPENED)；
 *   下游：gateway.downstream.base-url（本地联调指向本进程 mock）；超时 300ms。
 * </pre>
 */
@Configuration
public class GatewayConfig {

    /**
     * 网关统一时钟。默认跟随系统时间（与 {@code Clock.systemUTC()} 行为一致）；
     * 测试可注入同一 Bean 冻结/推进时间，确定性验证窗口边界与并发配额。
     */
    @Bean
    public AdjustableClock systemClock() {
        return new AdjustableClock();
    }

    @Bean
    public RateLimitConfig rateLimitConfig() {
        return RateLimitConfig.defaultLocalConfig();
    }

    @Bean
    public VersionNegotiator versionNegotiator() {
        return VersionNegotiator.defaultLocalNegotiator();
    }

    @Bean
    public ApiGroupConfig apiGroupConfig(VersionNegotiator versionNegotiator) {
        return ApiGroupConfig.defaultLocalConfig(versionNegotiator.supportedVersions());
    }

    @Bean
    public RouteRegistry routeRegistry(ApiGroupConfig apiGroupConfig) {
        return new RouteRegistry(apiGroupConfig);
    }

    @Bean
    public GroupAuthorizer groupAuthorizer() {
        return new GroupAuthorizer();
    }

    @Bean
    public GroupQuotaResolver groupQuotaResolver(ApiGroupConfig apiGroupConfig,
                                                 RateLimitConfig rateLimitConfig) {
        return new GroupQuotaResolver(apiGroupConfig, rateLimitConfig, Map.of());
    }

    @Bean
    public RateLimiter rateLimiter(GroupQuotaResolver groupQuotaResolver, Clock clock) {
        return new RateLimiter(groupQuotaResolver, clock);
    }

    @Bean
    public DownstreamClient downstreamClient(@Value("${gateway.downstream.base-url}") String baseUrl,
                                             @Value("${gateway.downstream.timeout-millis:300}") long timeoutMillis) {
        return new DownstreamClient(baseUrl, timeoutMillis);
    }

    @Bean
    public GatewayOrchestrator gatewayOrchestrator(VersionNegotiator versionNegotiator,
                                                   RouteRegistry routeRegistry,
                                                   ApiGroupConfig apiGroupConfig,
                                                   GroupAuthorizer groupAuthorizer,
                                                   RateLimiter rateLimiter,
                                                   DownstreamClient downstreamClient) {
        return new GatewayOrchestrator(versionNegotiator, routeRegistry, apiGroupConfig::policyFor,
                groupAuthorizer, rateLimiter, downstreamClient);
    }

    @Bean
    public TraceIdFilter traceIdFilter() {
        return new TraceIdFilter();
    }
}
