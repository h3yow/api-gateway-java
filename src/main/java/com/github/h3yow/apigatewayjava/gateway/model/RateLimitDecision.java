package com.github.h3yow.apigatewayjava.gateway.model;

/**
 * 限流判定结论。acquired=true 表示已原子扣减 1 个配额。
 *
 * @param groupId 计数所属接口组（计数维度为 调用方 × 版本 × 接口组，组间互不占用额度）
 */
public record RateLimitDecision(boolean acquired,
                                RejectReason rejectReason,
                                long limit,
                                long used,
                                long remaining,
                                long windowStart,
                                long windowEnd,
                                long retryAfterMillis,
                                String groupId,
                                String basis) {

    /** 兼容旧的“调用方 × 版本”维度构造（无接口组）。 */
    public RateLimitDecision(boolean acquired,
                             RejectReason rejectReason,
                             long limit,
                             long used,
                             long remaining,
                             long windowStart,
                             long windowEnd,
                             long retryAfterMillis,
                             String basis) {
        this(acquired, rejectReason, limit, used, remaining, windowStart, windowEnd,
                retryAfterMillis, null, basis);
    }
}
