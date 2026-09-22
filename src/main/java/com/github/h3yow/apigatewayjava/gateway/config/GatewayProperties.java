package com.github.h3yow.apigatewayjava.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 网关本地配置：调用方策略表（保留 key "default" 为未登记调用方的默认策略）
 * 与下游连接参数。
 */
@ConfigurationProperties(prefix = "gateway")
public class GatewayProperties {

    /** 配置表中默认策略的保留 key；未登记调用方一律落入该策略，绝不无限放行。 */
    public static final String DEFAULT_CALLER = "default";

    private Map<String, CallerConfig> callers = new LinkedHashMap<>();
    private DownstreamConfig downstream = new DownstreamConfig();

    public Map<String, CallerConfig> getCallers() {
        return callers;
    }

    public void setCallers(Map<String, CallerConfig> callers) {
        this.callers = callers;
    }

    public DownstreamConfig getDownstream() {
        return downstream;
    }

    public void setDownstream(DownstreamConfig downstream) {
        this.downstream = downstream;
    }

    /**
     * 解析调用方的生效策略：配置表中存在专用条目则使用之，否则回落到 "default" 条目；
     * 若连默认条目都不存在，返回一条“拒绝一切”的保守策略（配额为 0），保证不无限放行。
     *
     * @return 生效策略与该策略是否来自默认回落
     */
    public ResolvedPolicy resolve(String callerId) {
        CallerConfig explicit = callerId == null ? null : callers.get(callerId);
        if (explicit != null) {
            return new ResolvedPolicy(toPolicy(explicit), false);
        }
        CallerConfig fallback = callers.get(DEFAULT_CALLER);
        if (fallback != null) {
            return new ResolvedPolicy(toPolicy(fallback), true);
        }
        return new ResolvedPolicy(new CallerPolicy(0L, 60_000L, EnumSet.noneOf(ApiVersion.class)), true);
    }

    private CallerPolicy toPolicy(CallerConfig config) {
        EnumSet<ApiVersion> versions = EnumSet.noneOf(ApiVersion.class);
        for (String raw : config.getVersions()) {
            versions.add(ApiVersion.valueOf(normalize(raw)));
        }
        return new CallerPolicy(config.getQuota(), config.getWindowMillis(), Set.copyOf(versions));
    }

    public static String normalize(String rawVersion) {
        return rawVersion.trim().toUpperCase(Locale.ROOT);
    }

    /**
     * 解析结果：生效策略 + 是否为默认回落（用于日志可解释性）。
     */
    public record ResolvedPolicy(CallerPolicy policy, boolean fallback) {
    }

    public static class CallerConfig {
        private long quota;
        private long windowMillis;
        private List<String> versions = new ArrayList<>();

        public long getQuota() {
            return quota;
        }

        public void setQuota(long quota) {
            this.quota = quota;
        }

        public long getWindowMillis() {
            return windowMillis;
        }

        public void setWindowMillis(long windowMillis) {
            this.windowMillis = windowMillis;
        }

        public List<String> getVersions() {
            return versions;
        }

        public void setVersions(List<String> versions) {
            this.versions = versions;
        }
    }

    public static class DownstreamConfig {
        private String baseUrl = "http://localhost:8080";
        private long timeoutMillis = 2000;

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public long getTimeoutMillis() {
            return timeoutMillis;
        }

        public void setTimeoutMillis(long timeoutMillis) {
            this.timeoutMillis = timeoutMillis;
        }
    }
}
