package com.github.h3yow.apigatewayjava.gateway.ratelimit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 按 {@link QuotaKey}（调用方 + 版本）维护固定窗口计数的限流器。
 *
 * <p>并发正确性：每个 key 持有一个 {@link AtomicReference} 指向不可变窗口状态，
 * 放行与计数自增通过一次 CAS 同时完成（compare-and-increment），
 * 因而在并发请求下不会重复放行，也不会漏计；窗口切换时以 CAS 原子重置，
 * 旧窗口的迟到请求不会污染新窗口。
 *
 * <p>窗口对齐到纪元整点（windowStart = now - floorMod(now, windowMillis)），
 * 因此重试建议时间（窗口剩余毫秒）对所有调用方确定且可解释。
 */
@Component
public class RateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RateLimiter.class);

    private final ConcurrentHashMap<QuotaKey, AtomicReference<Window>> windows = new ConcurrentHashMap<>();

    /**
     * 尝试占用一个配额。
     *
     * @param quota        当前窗口允许的请求总数（0 表示直接拒绝）
     * @param windowMillis 固定窗口大小（毫秒）
     * @param clock        时间源（生产注入系统时钟，测试注入可控时钟）
     */
    public RateLimitDecision tryAcquire(QuotaKey key, long quota, long windowMillis, Clock clock) {
        if (windowMillis <= 0) {
            throw new IllegalArgumentException("windowMillis must be positive: " + windowMillis);
        }
        long now = clock.millis();
        long windowStart = now - Math.floorMod(now, windowMillis);
        AtomicReference<Window> ref =
                windows.computeIfAbsent(key, k -> new AtomicReference<>(new Window(windowStart, 0L)));

        while (true) {
            Window current = ref.get();

            // 窗口切换：原子地把过期窗口重置为新窗口的零计数，然后重试判定。
            if (current.startMillis() != windowStart) {
                if (current.startMillis() > windowStart) {
                    // 时钟回拨：以窗口内既有状态为准，拒绝本次获取，避免突破配额。
                    log.warn("Clock moved backwards for key={}, windowStart={} < current={}",
                            key, windowStart, current.startMillis());
                    return RateLimitDecision.reject(
                            RateLimitReason.LOCAL_QUOTA_EXCEEDED, windowMillis, windowStart, windowMillis);
                }
                ref.compareAndSet(current, new Window(windowStart, 0L));
                continue;
            }

            long used = current.count();
            long retryAfterMillis = Math.max(0L, windowStart + windowMillis - now);
            if (used >= quota) {
                log.info("限流判定 key={} 拒绝原因={} 已用={} 配额={} 窗口起点={}ms 建议重试={}ms",
                        key, RateLimitReason.LOCAL_QUOTA_EXCEEDED, used, quota, windowStart, retryAfterMillis);
                return RateLimitDecision.reject(
                        RateLimitReason.LOCAL_QUOTA_EXCEEDED, retryAfterMillis, windowStart, windowMillis);
            }

            Window next = new Window(windowStart, used + 1);
            if (ref.compareAndSet(current, next)) {
                long remaining = quota - used - 1;
                log.info("限流判定 key={} 放行 已用={} 剩余={} 配额={} 窗口起点={}ms 窗口大小={}ms",
                        key, used + 1, remaining, quota, windowStart, windowMillis);
                return RateLimitDecision.allow(remaining, windowStart, windowMillis);
            }
            // CAS 失败说明有并发请求抢先，自旋重试整个判定。
        }
    }

    /**
     * 测试/诊断用：读取某 key 当前窗口已用计数（不产生扣减）。
     */
    public long currentUsed(QuotaKey key, Clock clock, long windowMillis) {
        AtomicReference<Window> ref = windows.get(key);
        if (ref == null) {
            return 0L;
        }
        long now = clock.millis();
        long windowStart = now - Math.floorMod(now, windowMillis);
        Window current = ref.get();
        return current.startMillis() == windowStart ? current.count() : 0L;
    }

    private record Window(long startMillis, long count) {
    }
}
