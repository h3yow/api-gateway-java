package com.github.h3yow.apigatewayjava.gateway.version;

import com.github.h3yow.apigatewayjava.gateway.config.ApiVersion;
import com.github.h3yow.apigatewayjava.gateway.config.CallerPolicy;
import com.github.h3yow.apigatewayjava.gateway.config.GatewayProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 版本协商：解析 X-Api-Version 声明并与调用方授权版本集合做兼容判定。
 *
 * <p>拒绝规则（均不静默回落到任何默认版本）：
 * <ul>
 *   <li>声明缺失或空白 → VERSION_MISSING；</li>
 *   <li>声明无法识别为已知版本标识 → VERSION_UNKNOWN；</li>
 *   <li>版本可识别但该调用方未被授权使用 → VERSION_NOT_SUPPORTED。</li>
 * </ul>
 * 判定为纯函数，对同一输入组合结论稳定。
 */
@Component
public class VersionNegotiator {

    private static final Logger log = LoggerFactory.getLogger(VersionNegotiator.class);

    public VersionDecision negotiate(String rawVersion, CallerPolicy policy) {
        if (rawVersion == null || rawVersion.isBlank()) {
            log.info("版本判定 原始声明=<缺失> 拒绝原因=VERSION_MISSING 授权版本={}",
                    policy.supportedVersions());
            return VersionDecision.reject(rawVersion, "VERSION_MISSING");
        }

        String normalized = GatewayProperties.normalize(rawVersion);
        ApiVersion version;
        try {
            version = ApiVersion.valueOf(normalized);
        } catch (IllegalArgumentException ex) {
            log.info("版本判定 原始声明={} 归一化={} 拒绝原因=VERSION_UNKNOWN 授权版本={}",
                    rawVersion, normalized, policy.supportedVersions());
            return VersionDecision.reject(rawVersion, "VERSION_UNKNOWN");
        }

        if (!policy.supportedVersions().contains(version)) {
            log.info("版本判定 原始声明={} 解析版本={} 拒绝原因=VERSION_NOT_SUPPORTED 授权版本={}",
                    rawVersion, version, policy.supportedVersions());
            return VersionDecision.reject(rawVersion, "VERSION_NOT_SUPPORTED");
        }

        log.info("版本判定 原始声明={} 解析版本={} 通过 授权版本={}",
                rawVersion, version, policy.supportedVersions());
        return VersionDecision.accept(version, rawVersion);
    }
}
