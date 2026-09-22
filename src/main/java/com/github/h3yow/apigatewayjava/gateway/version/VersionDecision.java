package com.github.h3yow.apigatewayjava.gateway.version;

import com.github.h3yow.apigatewayjava.gateway.config.ApiVersion;

/**
 * 版本协商结论。accepted=false 时 version 为 null，reason 给出拒绝原因。
 */
public record VersionDecision(boolean accepted,
                              ApiVersion version,
                              String rawVersion,
                              String reason) {

    public static VersionDecision accept(ApiVersion version, String rawVersion) {
        return new VersionDecision(true, version, rawVersion, null);
    }

    public static VersionDecision reject(String rawVersion, String reason) {
        return new VersionDecision(false, null, rawVersion, reason);
    }
}
