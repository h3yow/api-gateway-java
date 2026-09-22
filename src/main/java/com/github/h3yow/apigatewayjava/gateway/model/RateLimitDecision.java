package com.github.h3yow.apigatewayjava.gateway.model;

/**
 * 限流判定结论。acquired=true 表示已原子扣减 1 个配额。
 */
public record RateLimitDecision(boolean acquired,
                                RejectReason rejectReason,
                                long limit,
                                long used,
                                long remaining,
                                long windowStart,
                                long windowEnd,
                                long retryAfterMillis,
                                String basis) {
}
