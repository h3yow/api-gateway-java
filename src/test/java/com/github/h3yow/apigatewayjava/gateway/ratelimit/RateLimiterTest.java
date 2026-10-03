package com.github.h3yow.apigatewayjava.gateway.ratelimit;

import com.github.h3yow.apigatewayjava.gateway.MutableClock;
import com.github.h3yow.apigatewayjava.gateway.model.ApiVersion;
import com.github.h3yow.apigatewayjava.gateway.model.QuotaPolicy;
import com.github.h3yow.apigatewayjava.gateway.model.RateLimitDecision;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
 * 固定窗口限流器单测：并发配额守恒、拒绝不扣减、窗口切换重置、维度独立、默认策略。
 * 每条断言均通过日志打印调用方、版本、配额与判定依据。
 */
class RateLimiterTest {

    private static final ApiVersion V10 = new ApiVersion(1, 0);
    private static final ApiVersion V11 = new ApiVersion(1, 1);

    private static RateLimitConfig config(Map<String, QuotaPolicy> extra) {
        Map<String, QuotaPolicy> map = new HashMap<>();
        map.put(RateLimitConfig.key(RateLimitConfig.DEFAULT_CALLER, RateLimitConfig.ANY_VERSION),
                new QuotaPolicy(1, 1_000));
        map.putAll(extra);
        return new RateLimitConfig(map);
    }

    private static void logDecision(String caller, ApiVersion v, RateLimitDecision d) {
        System.out.printf("[RateLimitTest] caller=%s version=%s acquired=%s quota=%d/%d remaining=%d "
                        + "window=[%d,%d) retryAfterMillis=%d reason=%s basis=%s%n",
                caller, v.httpValue(), d.acquired(), d.used(), d.limit(), d.remaining(),
                d.windowStart(), d.windowEnd(), d.retryAfterMillis(),
                d.rejectReason(), d.basis());
    }

    @Test
    void concurrentRequests_quotaIsConserved_noOverAdmit_noUnderAdmit() throws Exception {
        RateLimitConfig cfg = config(Map.of(
                RateLimitConfig.key("caller-a", "v1.0"), new QuotaPolicy(50, 1_000)));
        MutableClock clock = new MutableClock(1_000);
        RateLimiter limiter = new RateLimiter(cfg, clock);

        int threads = 20;
        int perThread = 10; // 共 200 个并发请求，配额 50
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger granted = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        ConcurrentLinkedQueue<Long> grantedUsages = new ConcurrentLinkedQueue<>();

        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        RateLimitDecision d = limiter.tryAcquire("caller-a", V10);
                        logDecision("caller-a", V10, d);
                        if (d.acquired()) {
                            granted.incrementAndGet();
                            grantedUsages.add(d.used());
                        } else {
                            rejected.incrementAndGet();
                            assertEquals(50, d.used(), "被拒绝时 used 必须停在 50");
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

        // 配额守恒：恰好 50 放行、150 拒绝，最终计数 50，放行序号恰好 1..50 无重复
        assertEquals(50, granted.get(), "放行数必须等于配额");
        assertEquals(150, rejected.get(), "拒绝数=总请求-配额");
        assertEquals(50, limiter.currentUsed("caller-a", V10));
        List<Long> usages = grantedUsages.stream().sorted().toList();
        for (int i = 0; i < 50; i++) {
            assertEquals(i + 1L, usages.get(i), "每次放行的已用量必须严格递增 1..50，无重复扣减");
        }
    }

    @Test
    void rejectedRequestsDoNotConsumeQuota() {
        RateLimitConfig cfg = config(Map.of(
                RateLimitConfig.key("caller-b", "v1.0"), new QuotaPolicy(2, 1_000)));
        RateLimiter limiter = new RateLimiter(cfg, new MutableClock(5_000));

        assertTrue(limiter.tryAcquire("caller-b", V10).acquired());
        assertTrue(limiter.tryAcquire("caller-b", V10).acquired());
        for (int i = 0; i < 5; i++) {
            RateLimitDecision d = limiter.tryAcquire("caller-b", V10);
            logDecision("caller-b", V10, d);
            assertFalse(d.acquired());
        }
        assertEquals(2, limiter.currentUsed("caller-b", V10), "拒绝不得扣减配额");
    }

    @Test
    void windowRollover_resetsCounter_andRetryAfterPointsToWindowEnd() {
        RateLimitConfig cfg = config(Map.of(
                RateLimitConfig.key("caller-a", "v1.0"), new QuotaPolicy(3, 1_000)));
        MutableClock clock = new MutableClock(999); // 第一窗口 [0,1000) 的尾部
        RateLimiter limiter = new RateLimiter(cfg, clock);

        RateLimitDecision d1 = limiter.tryAcquire("caller-a", V10);
        RateLimitDecision d2 = limiter.tryAcquire("caller-a", V10);
        RateLimitDecision d3 = limiter.tryAcquire("caller-a", V10);
        RateLimitDecision reject = limiter.tryAcquire("caller-a", V10);
        logDecision("caller-a", V10, reject);
        assertTrue(d1.acquired() && d2.acquired() && d3.acquired());
        assertFalse(reject.acquired());
        assertEquals(1L, reject.retryAfterMillis(), "距窗口结束 1000ms 还差 1ms");
        assertEquals(0L, reject.windowStart());
        assertEquals(1_000L, reject.windowEnd());

        // 窗口切换：1000ms 起进入 [1000,2000)，计数归零、配额恢复
        clock.setMillis(1_000);
        RateLimitDecision nextWindow = limiter.tryAcquire("caller-a", V10);
        logDecision("caller-a", V10, nextWindow);
        assertTrue(nextWindow.acquired());
        assertEquals(1L, nextWindow.used());
        assertEquals(1_000L, nextWindow.windowStart());
        assertEquals(2_000L, nextWindow.windowEnd());
    }

    @Test
    void quotasAreIndependentAcrossVersionsAndCallers() {
        RateLimitConfig cfg = config(new HashMap<>(Map.of(
                RateLimitConfig.key("caller-a", "v1.0"), new QuotaPolicy(1, 1_000),
                RateLimitConfig.key("caller-a", "v1.1"), new QuotaPolicy(1, 1_000),
                RateLimitConfig.key("caller-b", "v1.0"), new QuotaPolicy(1, 1_000))));
        RateLimiter limiter = new RateLimiter(cfg, new MutableClock(0));

        assertTrue(limiter.tryAcquire("caller-a", V10).acquired());
        assertFalse(limiter.tryAcquire("caller-a", V10).acquired());
        // 同调用方不同版本配额独立
        assertTrue(limiter.tryAcquire("caller-a", V11).acquired());
        // 不同调用方同版本配额独立
        assertTrue(limiter.tryAcquire("caller-b", V10).acquired());
    }

    @Test
    void unknownCallerFallsBackToExplicitDefaultPolicy_notUnlimited() {
        RateLimitConfig cfg = config(new HashMap<>(Map.of(
                RateLimitConfig.key("caller-a", "v1.0"), new QuotaPolicy(100, 1_000))));
        RateLimiter limiter = new RateLimiter(cfg, new MutableClock(0));

        RateLimitDecision d1 = limiter.tryAcquire("stranger", V10);
        RateLimitDecision d2 = limiter.tryAcquire("stranger", V10);
        logDecision("stranger", V10, d1);
        logDecision("stranger", V10, d2);
        assertTrue(d1.acquired());
        assertFalse(d2.acquired(), "未登记调用方必须落到默认 1/s 策略，第二请求即被限流");
        assertTrue(d1.basis().contains("fallback default"));
    }
}
