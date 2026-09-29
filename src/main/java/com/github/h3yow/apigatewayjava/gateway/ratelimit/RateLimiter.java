package com.github.h3yow.apigatewayjava.gateway.ratelimit;

import com.github.h3yow.apigatewayjava.gateway.model.ApiVersion;
import com.github.h3yow.apigatewayjava.gateway.model.QuotaResolution;
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
 *     <li>计数维度为 {@code 调用方|接口组|版本}，三维任一不同即互不占用额度
 *         （同一调用方+版本访问不同接口组，计数器彼此独立）；</li>
 *     <li>窗口边界按 epoch 对齐：windowStart = floor(now / windowMillis) * windowMillis，
 *         窗口切换瞬间旧计数作废、新窗口从 0 开始，边界确定可测；</li>
 *     <li>{@code tryAcquire} 通过 CAS 自旋完成“检查窗口→必要时翻窗→扣减”，
 *         放行与扣减在同一次原子状态迁移里发生：不会重复放行、不会重复扣减，
 *         拒绝路径不修改任何计数；</li>
 *     <li>拒绝时给出 retryAfterMillis = 距当前窗口结束的毫秒数。</li>
 * </ul>
 */
public class RateLimiter {

    /** 旧两维入口（caller×version）使用的内部组标识，保持既有测试与无组场景行为一致。 */
    public static final String DEFAULT_GROUP = "__caller_version__";

    private final RateLimitConfig config;
    private final Clock clock;
    private final ConcurrentHashMap<String, AtomicReference<Window>> windows = new ConcurrentHashMap<>();

    public RateLimiter(RateLimitConfig config, Clock clock) {
        this.config = config;
        this.clock = clock;
    }

    /**
     * 旧两维入口：等价于归入内部默认组 {@link #DEFAULT_GROUP}。
     */
    public RateLimitDecision tryAcquire(String caller, ApiVersion version) {
        var policy = config.policyFor(caller, version);
        return acquire(dimensionKey(caller, DEFAULT_GROUP, version.httpValue()),
                policy.limit(), policy.windowMillis(), config.basisOf(caller, version));
    }

    /**
     * 接口组维度入口：配额来自 {@link QuotaResolution}（组自有或回退），
     * 计数键严格按 caller|group|version 隔离。
     */
    public RateLimitDecision tryAcquire(QuotaResolution quota) {
        String key = dimensionKey(quota.caller(), quota.groupId(), quota.version().httpValue());
        return acquire(key, quota.policy().limit(), quota.policy().windowMillis(), quota.basis());
    }

    private RateLimitDecision acquire(String key, long limit, long windowMillis, String basis) {
        long now = clock.millis();
        AtomicReference<Window> ref = windows.computeIfAbsent(key, k -> new AtomicReference<>());

        while (true) {
            Window current = ref.get();
            Window active = rollOrCreate(current, limit, windowMillis, now);

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
                        basis
                                + "; dimension=" + key
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
                        basis
                                + "; dimension=" + key
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

    private static String dimensionKey(String caller, String groupId, String versionKey) {
        return caller + "|" + groupId + "|" + versionKey;
    }

    /**
     * 只读视图：返回旧两维默认组当前窗口已用量，不产生扣减。仅供既有测试与排障。
     */
    public long currentUsed(String caller, ApiVersion version) {
        return currentUsed(caller, DEFAULT_GROUP, version);
    }

    /**
     * 只读视图：返回指定接口组当前窗口已用量，不产生扣减。仅供测试与排障。
     */
    public long currentUsed(String caller, String groupId, ApiVersion version) {
        long now = clock.millis();
        var ref = windows.get(dimensionKey(caller, groupId, version.httpValue()));
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
