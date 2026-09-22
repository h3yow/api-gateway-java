package com.github.h3yow.apigatewayjava.gateway.ratelimit;

/**
 * 一次配额判定结论。allowed=false 时携带可区分原因与建议等待毫秒数。
 */
public record RateLimitDecision(boolean allowed,
                                RateLimitReason reason,
                                long remaining,
                                long retryAfterMillis,
                                long windowStartMillis,
                                long windowSizeMillis) {

    public static RateLimitDecision allow(long remaining, long windowStartMillis, long windowSizeMillis) {
        return new RateLimitDecision(true, null, remaining, 0L, windowStartMillis, windowSizeMillis);
    }

    public static RateLimitDecision reject(RateLimitReason reason, long retryAfterMillis,
                                           long windowStartMillis, long windowSizeMillis) {
        return new RateLimitDecision(false, reason, 0L, retryAfterMillis, windowStartMillis, windowSizeMillis);
    }
}
