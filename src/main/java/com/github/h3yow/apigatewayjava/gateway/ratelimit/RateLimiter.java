package com.github.h3yow.apigatewayjava.gateway.ratelimit;

import com.github.h3yow.apigatewayjava.gateway.model.ApiVersion;
import com.github.h3yow.apigatewayjava.gateway.model.RateLimitDecision;
import com.github.h3yow.apigatewayjava.gateway.model.RejectReason;

import java.time.Clock;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 固定窗口（fixed window）限流器。
 *
 * 语义：
 * <ul>
 *     <li>计数维度为 {@code 调用方|版本}，不同维度互不影响；</li>
 *     <li>窗口边界按 epoch 对齐：windowStart = floor(now / windowMillis) * windowMillis，
 *         窗口切换瞬间旧计数作废、新窗口从 0 开始，边界确定可测；</li>
 *     <li>{@code tryAcquire} 通过 CAS 自旋完成“检查窗口→必要时翻窗→扣减”，
 *         放行与扣减在同一次原子状态迁移里发生：不会重复放行、不会重复扣减，
 *         拒绝路径不修改任何计数；</li>
 *     <li>拒绝时给出 retryAfterMillis = 距当前窗口结束的毫秒数。</li>
 * </ul>
 */
public class RateLimiter {

    private final RateLimitConfig config;
    private final Clock clock;
    private final ConcurrentHashMap<String, AtomicReference<Window>> windows = new ConcurrentHashMap<>();

    public RateLimiter(RateLimitConfig config, Clock clock) {
        this.config = config;
        this.clock = clock;
    }

    public RateLimitDecision tryAcquire(String caller, ApiVersion version) {
        var policy = config.policyFor(caller, version);
        long now = clock.millis();
        String key = RateLimitConfig.key(caller, version.httpValue());
        AtomicReference<Window> ref = windows.computeIfAbsent(key, k -> new AtomicReference<>());

        while (true) {
            Window current = ref.get();
            Window active = rollOrCreate(current, policy.limit(), policy.windowMillis(), now);

            if (active.used() >= active.limit()) {
                long retryAfter = Math.max(0L, active.end() - now);
                return new RateLimitDecision(
                        false,
                        RejectReason.RATE_LIMITED_QUOTA_EXHAUSTED,
                        active.limit(),
                        active.used(),
                        0L,
                        active.start(),
                        active.end(),
                        retryAfter,
                        config.basisOf(caller, version)
                                + "; window [" + active.start() + "," + active.end() + ")"
                                + " used=" + active.used() + "/" + active.limit()
                                + "; retryAfterMillis=" + retryAfter);
            }

            Window next = new Window(active.start(), active.end(), active.used() + 1,
                    active.limit(), active.windowMillis());
            if (ref.compareAndSet(current, next)) {
                return new RateLimitDecision(
                        true,
                        null,
                        next.limit(),
                        next.used(),
                        next.limit() - next.used(),
                        next.start(),
                        next.end(),
                        0L,
                        config.basisOf(caller, version)
                                + "; window [" + next.start() + "," + next.end() + ")"
                                + " used=" + next.used() + "/" + next.limit()
                                + "; quota atomically acquired (1 per request)");
            }
            // CAS 失败说明并发下窗口被其他线程推进，自旋重读——本线程尚未扣减，故无重复计数。
        }
    }

    private Window rollOrCreate(Window current, long limit, long windowMillis, long now) {
        if (current == null || now >= current.end()) {
            long start = now - Math.floorMod(now, windowMillis);
            return new Window(start, start + windowMillis, 0L, limit, windowMillis);
        }
        return current;
    }

    /**
     * 只读视图：返回当前（按传入时钟判定）窗口已用量，不产生扣减。仅供测试与排障。
     */
    public long currentUsed(String caller, ApiVersion version) {
        long now = clock.millis();
        var ref = windows.get(RateLimitConfig.key(caller, version.httpValue()));
        if (ref == null) {
            return 0L;
        }
        Window w = ref.get();
        if (w == null || now >= w.end()) {
            return 0L;
        }
        return w.used();
    }

    private record Window(long start, long end, long used, long limit, long windowMillis) {
    }
}
