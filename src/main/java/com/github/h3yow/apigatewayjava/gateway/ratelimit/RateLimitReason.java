package com.github.h3yow.apigatewayjava.gateway.ratelimit;

/**
 * 限流原因，必须可区分：
 * LOCAL_QUOTA_EXCEEDED  — 本地固定窗口配额耗尽；
 * DOWNSTREAM_THROTTLED  — 请求通过了本地配额，但被下游以 429 拒绝。
 */
public enum RateLimitReason {
    LOCAL_QUOTA_EXCEEDED,
    DOWNSTREAM_THROTTLED
}
