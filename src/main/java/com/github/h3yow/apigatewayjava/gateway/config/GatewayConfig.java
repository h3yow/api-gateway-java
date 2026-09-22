package com.github.h3yow.apigatewayjava.gateway.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * 网关装配：启用配置绑定并提供系统时钟（测试可替换为固定/可控时钟）。
 */
@Configuration
@EnableConfigurationProperties(GatewayProperties.class)
public class GatewayConfig {

    @Bean
    public Clock systemClock() {
        return Clock.systemUTC();
    }
}
