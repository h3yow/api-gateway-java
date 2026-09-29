package com.github.h3yow.apigatewayjava.gateway.routing;

import com.github.h3yow.apigatewayjava.gateway.model.ApiVersion;
import com.github.h3yow.apigatewayjava.gateway.model.InterfaceGroupConfig;
import com.github.h3yow.apigatewayjava.gateway.model.InterfaceStatus;
import com.github.h3yow.apigatewayjava.gateway.model.QuotaPolicy;
import com.github.h3yow.apigatewayjava.gateway.model.QuotaResolution;
import com.github.h3yow.apigatewayjava.gateway.model.RateLimitDecision;
import com.github.h3yow.apigatewayjava.gateway.ratelimit.RateLimitConfig;
import com.github.h3yow.apigatewayjava.gateway.ratelimit.RateLimiter;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 接口组维度限流单测（全部使用可控时钟，确定性复现）：
 * 组间配额隔离、回退配额仍按组隔离、组×版本隔离、并发守恒、窗口切换重置、拒绝不扣减。
 */
class RateLimiterGroupTest {

    private static final ApiVersion V10 = new ApiVersion(1, 0);
    private static final ApiVersion V11 = new ApiVersion(1, 1);

    private RateLimitConfig rateConfig() {
        Map<String, QuotaPolicy> map = new HashMap<>();
        map.put(RateLimitConfig.key("caller-a", "v1.0"), new QuotaPolicy(5, 1_000));
        map.put(RateLimitConfig.key("caller-a", "v1.1"), new QuotaPolicy(10, 1_000));
        map.put(RateLimitConfig.key(RateLimitConfig.DEFAULT_CALLER, RateLimitConfig.ANY_VERSION),
                new QuotaPolicy(1, 1_000));
        return new RateLimitConfig(map);
    }

    private InterfaceGroupConfig group(String id, QuotaPolicy quota) {
        return new InterfaceGroupConfig(id, InterfaceStatus.ACTIVE, List.of(V10, V11),
                Set.of(), null, quota);
    }

    private QuotaResolution resolution(RateLimitConfig cfg, String caller, InterfaceGroupConfig g,
                                       ApiVersion v) {
        return new QuotaResolver(cfg).resolve(caller, g, v);
    }

    @Test
    void groupsDoNotShareQuotaEvenForSameCallerAndVersion() {
        RateLimitConfig cfg = rateConfig();
        RateLimiter limiter = new RateLimiter(cfg, new MutableClock(1_000));
        var orders = group("orders", new QuotaPolicy(2, 1_000));
        var reports = group("reports", new QuotaPolicy(2, 1_000));

        // orders 用满 2 个
        assertTrue(limiter.tryAcquire(resolution(cfg, "caller-a", orders, V10)).acquired());
        assertTrue(limiter.tryAcquire(resolution(cfg, "caller-a", orders, V10)).acquired());
        assertFalse(limiter.tryAcquire(resolution(cfg, "caller-a", orders, V10)).acquired(),
                "orders 组自身配额耗尽");
        // 同 caller 同 version 的 reports 组完全不受影响
        assertTrue(limiter.tryAcquire(resolution(cfg, "caller-a", reports, V10)).acquired(),
                "接口组之间不得互相占用额度");
        assertTrue(limiter.tryAcquire(resolution(cfg, "caller-a", reports, V10)).acquired());

        assertEquals(2, limiter.currentUsed("caller-a", "orders", V10));
        assertEquals(2, limiter.currentUsed("caller-a", "reports", V10));
    }

    @Test
    void fallbackCallerVersionQuotaIsStillIsolatedByGroup() {
        RateLimitConfig cfg = rateConfig();
        RateLimiter limiter = new RateLimiter(cfg, new MutableClock(1_000));
        var reports = group("reports", null);   // 回退 caller-a|v1.0 = 5
        var stats = group("stats", null);       // 同样回退 5

        for (int i = 0; i < 5; i++) {
            assertTrue(limiter.tryAcquire(resolution(cfg, "caller-a", reports, V10)).acquired());
        }
        assertFalse(limiter.tryAcquire(resolution(cfg, "caller-a", reports, V10)).acquired(),
                "回退配额在 reports 组内耗尽");
        for (int i = 0; i < 5; i++) {
            assertTrue(limiter.tryAcquire(resolution(cfg, "caller-a", stats, V10)).acquired(),
                    "回退同一限额值，但 stats 组有自己的计数器，不受 reports 消耗影响");
        }
        assertEquals(5, limiter.currentUsed("caller-a", "reports", V10));
        assertEquals(5, limiter.currentUsed("caller-a", "stats", V10));
    }

    @Test
    void sameGroupKeepsVersionsIndependent() {
        RateLimitConfig cfg = rateConfig();
        RateLimiter limiter = new RateLimiter(cfg, new MutableClock(1_000));
        var orders = group("orders", new QuotaPolicy(2, 1_000));
        assertTrue(limiter.tryAcquire(resolution(cfg, "caller-a", orders, V10)).acquired());
        assertTrue(limiter.tryAcquire(resolution(cfg, "caller-a", orders, V10)).acquired());
        assertFalse(limiter.tryAcquire(resolution(cfg, "caller-a", orders, V10)).acquired());
        // 同组不同版本独立
        assertTrue(limiter.tryAcquire(resolution(cfg, "caller-a", orders, V11)).acquired());
    }

    @Test
    void concurrentAcrossGroups_quotaConserved() throws Exception {
        RateLimitConfig cfg = rateConfig();
        RateLimiter limiter = new RateLimiter(cfg, new MutableClock(5_000));
        var orders = group("orders", new QuotaPolicy(10, 1_000));

        int threads = 16;
        int perThread = 4; // 64 请求抢 orders 组 10 配额，时钟固定
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger granted = new AtomicInteger();
        ConcurrentLinkedQueue<Long> usages = new ConcurrentLinkedQueue<>();
        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        RateLimitDecision d = limiter.tryAcquire(resolution(cfg, "caller-a", orders, V11));
                        if (d.acquired()) {
                            granted.incrementAndGet();
                            usages.add(d.used());
                        } else {
                            assertEquals(10, d.used());
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS));
        pool.shutdown();
        assertEquals(10, granted.get(), "组配额并发守恒：恰好 10 个放行");
        List<Long> sorted = usages.stream().sorted().toList();
        for (int i = 0; i < 10; i++) {
            assertEquals(i + 1L, sorted.get(i), "放行序号严格 1..10 无重复扣减");
        }
    }

    @Test
    void windowRollover_resetsGroupCounter_withControlledClock() {
        RateLimitConfig cfg = rateConfig();
        MutableClock clock = new MutableClock(999);
        RateLimiter limiter = new RateLimiter(cfg, clock);
        var orders = group("orders", new QuotaPolicy(2, 1_000));

        assertTrue(limiter.tryAcquire(resolution(cfg, "caller-a", orders, V10)).acquired());
        RateLimitDecision second = limiter.tryAcquire(resolution(cfg, "caller-a", orders, V10));
        assertTrue(second.acquired());
        RateLimitDecision reject = limiter.tryAcquire(resolution(cfg, "caller-a", orders, V10));
        assertFalse(reject.acquired());
        assertEquals(1L, reject.retryAfterMillis(), "窗口 [0,1000) 在 now=999 时剩余 1ms");
        assertTrue(reject.basis().contains("dimension=caller-a|orders|v1.0"));

        clock.setMillis(1_000); // 确定性翻窗，无真实等待
        RateLimitDecision next = limiter.tryAcquire(resolution(cfg, "caller-a", orders, V10));
        assertTrue(next.acquired());
        assertEquals(1L, next.used());
        assertEquals(1_000L, next.windowStart());
        assertEquals(2_000L, next.windowEnd());
    }

    /** 可控毫秒时钟（确定性窗口测试，不依赖墙钟）。 */
    static final class MutableClock extends Clock {
        private volatile long millis;

        MutableClock(long millis) {
            this.millis = millis;
        }

        void setMillis(long millis) {
            this.millis = millis;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(millis);
        }
    }
}
