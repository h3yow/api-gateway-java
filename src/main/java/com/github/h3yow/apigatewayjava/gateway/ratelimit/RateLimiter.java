package com.github.h3yow.apigatewayjava.gateway.ratelimit;

import com.github.h3yow.apigatewayjava.gateway.group.QuotaResolver;
import com.github.h3yow.apigatewayjava.gateway.model.ApiVersion;
import com.github.h3yow.apigatewayjava.gateway.model.QuotaPolicy;
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
 *     <li>计数维度为 {@code 调用方|版本|接口组}，不同维度互不影响；
 *         同一调用方同一版本访问不同接口组时各自独立计数，互不占用额度；</li>
 *     <li>每条维度的 {@link QuotaPolicy} 由 {@link QuotaResolver} 解析：接口组可声明
 *         自己的限额与窗口，未声明则回退到“调用方 × 版本”既有策略，始终有限；</li>
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
    private final QuotaResolver quotaResolver;
    private final Clock clock;
    private final ConcurrentHashMap<String, AtomicReference<Window>> windows = new ConcurrentHashMap<>();

    /** 旧构造：仅按“调用方 × 版本”配额。 */
    public RateLimiter(RateLimitConfig config, Clock clock) {
        this.config = config;
        this.quotaResolver = null;
        this.clock = clock;
    }

    /** 新构造：配额按“调用方 × 版本 × 接口组”解析。 */
    public RateLimiter(QuotaResolver quotaResolver, Clock clock) {
        this.config = null;
        this.quotaResolver = quotaResolver;
        this.clock = clock;
    }

    /** 旧维度（无接口组）。 */
    public RateLimitDecision tryAcquire(String caller, ApiVersion version) {
        QuotaPolicy policy = config.policyFor(caller, version);
        String key = RateLimitConfig.key(caller, version.httpValue());
        return acquire(key, policy, config.basisOf(caller, version), null, caller, version);
    }

    /** 接口组维度：同一调用方×版本在不同组之间独立计数。 */
    public RateLimitDecision tryAcquire(String caller, ApiVersion version, String groupId) {
        QuotaResolver.Resolved resolved = quotaResolver.resolve(caller, version, groupId);
        String key = key(caller, version.httpValue(), groupId);
        return acquire(key, resolved.policy(), resolved.basis(), groupId, caller, version);
    }

    private RateLimitDecision acquire(String key, QuotaPolicy policy, String basis, String groupId,
                                      String caller, ApiVersion version) {
        long now = clock.millis();
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
                        groupId,
                        basis
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
                        groupId,
                        basis
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

    /** 旧维度只读视图：当前窗口已用量，不产生扣减。 */
    public long currentUsed(String caller, ApiVersion version) {
        return currentUsed(key(caller, version.httpValue(), null));
    }

    /** 接口组维度只读视图：当前窗口已用量，不产生扣减。 */
    public long currentUsed(String caller, ApiVersion version, String groupId) {
        return currentUsed(key(caller, version.httpValue(), groupId));
    }

    private long currentUsed(String key) {
        long now = clock.millis();
        var ref = windows.get(key);
        if (ref == null) {
            return 0L;
        }
        Window w = ref.get();
        if (w == null || now >= w.end()) {
            return 0L;
        }
        return w.used();
    }

    private static String key(String caller, String versionKey, String groupId) {
        return groupId == null
                ? RateLimitConfig.key(caller, versionKey)
                : caller + "|" + versionKey + "|" + groupId;
    }

    private record Window(long start, long end, long used, long limit, long windowMillis) {
    }
}
