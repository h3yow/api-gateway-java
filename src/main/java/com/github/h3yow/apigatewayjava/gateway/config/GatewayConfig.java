package com.github.h3yow.apigatewayjava.gateway.config;

import com.github.h3yow.apigatewayjava.gateway.core.GatewayOrchestrator;
import com.github.h3yow.apigatewayjava.gateway.downstream.DownstreamClient;
import com.github.h3yow.apigatewayjava.gateway.ratelimit.RateLimitConfig;
import com.github.h3yow.apigatewayjava.gateway.ratelimit.RateLimiter;
import com.github.h3yow.apigatewayjava.gateway.routing.InterfaceGroupGate;
import com.github.h3yow.apigatewayjava.gateway.routing.QuotaResolver;
import com.github.h3yow.apigatewayjava.gateway.routing.RouteTable;
import com.github.h3yow.apigatewayjava.gateway.version.VersionNegotiator;
import com.github.h3yow.apigatewayjava.gateway.web.TraceIdFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * 网关本地装配。
 * <pre>
 *   配额：caller-a v1.0=5/s、v1.1=10/s；caller-b 任意版本=3/s；未登记调用方=1/s
 *         （接口组可覆盖为组自有配额，否则回退该策略，计数仍按接口组隔离）
 *   版本：系统支持 v1.0、v1.1；caller-a 两者皆可；caller-b 仅 v1.0；默认仅 v1.0
 *   接口组：orders(自有2/s)、reports(回退)、beta(灰度)、legacy(暂停)、future(未开放)
 *   下游：gateway.downstream.base-url（本地联调指向本进程 mock）；超时 300ms
 * </pre>
 */
@Configuration
public class GatewayConfig {

    @Bean
    public Clock systemClock() {
        return Clock.systemUTC();
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
    public RouteTable routeTable(VersionNegotiator versionNegotiator) {
        return RouteTable.defaultLocalTable(versionNegotiator.supportedVersions());
    }

    @Bean
    public InterfaceGroupGate interfaceGroupGate() {
        return new InterfaceGroupGate();
    }

    @Bean
    public QuotaResolver quotaResolver(RateLimitConfig rateLimitConfig) {
        return new QuotaResolver(rateLimitConfig);
    }

    @Bean
    public RateLimiter rateLimiter(RateLimitConfig config, Clock clock) {
        return new RateLimiter(config, clock);
    }

    @Bean
    public DownstreamClient downstreamClient(@Value("${gateway.downstream.base-url}") String baseUrl,
                                             @Value("${gateway.downstream.timeout-millis:300}") long timeoutMillis) {
        return new DownstreamClient(baseUrl, timeoutMillis);
    }

    @Bean
    public GatewayOrchestrator gatewayOrchestrator(RouteTable routeTable,
                                                   InterfaceGroupGate interfaceGroupGate,
                                                   VersionNegotiator versionNegotiator,
                                                   QuotaResolver quotaResolver,
                                                   RateLimiter rateLimiter,
                                                   DownstreamClient downstreamClient) {
        return new GatewayOrchestrator(routeTable, interfaceGroupGate, versionNegotiator,
                quotaResolver, rateLimiter, downstreamClient);
    }

    @Bean
    public TraceIdFilter traceIdFilter() {
        return new TraceIdFilter();
    }
}
