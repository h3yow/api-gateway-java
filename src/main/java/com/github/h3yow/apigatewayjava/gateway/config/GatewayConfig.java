package com.github.h3yow.apigatewayjava.gateway.config;

import com.github.h3yow.apigatewayjava.gateway.core.GatewayOrchestrator;
import com.github.h3yow.apigatewayjava.gateway.downstream.DownstreamClient;
import com.github.h3yow.apigatewayjava.gateway.ratelimit.RateLimitConfig;
import com.github.h3yow.apigatewayjava.gateway.ratelimit.RateLimiter;
import com.github.h3yow.apigatewayjava.gateway.version.VersionNegotiator;
import com.github.h3yow.apigatewayjava.gateway.web.TraceIdFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * 网关本地装配。
 * 默认策略（可由测试替换 Bean）：
 * <pre>
 *   配额：caller-a v1.0=5/s、v1.1=10/s；caller-b 任意版本=3/s；未登记调用方=1/s
 *   版本：系统支持 v1.0、v1.1；caller-a 两者皆可；caller-b 仅 v1.0；默认仅 v1.0
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
    public RateLimiter rateLimiter(RateLimitConfig config, Clock clock) {
        return new RateLimiter(config, clock);
    }

    @Bean
    public DownstreamClient downstreamClient(@Value("${gateway.downstream.base-url}") String baseUrl,
                                             @Value("${gateway.downstream.timeout-millis:300}") long timeoutMillis) {
        return new DownstreamClient(baseUrl, timeoutMillis);
    }

    @Bean
    public GatewayOrchestrator gatewayOrchestrator(VersionNegotiator versionNegotiator,
                                                   RateLimiter rateLimiter,
                                                   DownstreamClient downstreamClient) {
        return new GatewayOrchestrator(versionNegotiator, rateLimiter, downstreamClient);
    }

    @Bean
    public TraceIdFilter traceIdFilter() {
        return new TraceIdFilter();
    }
}
