package com.github.h3yow.apigatewayjava.gateway;

import com.github.h3yow.apigatewayjava.gateway.config.ApiVersion;
import com.github.h3yow.apigatewayjava.gateway.ratelimit.QuotaKey;
import com.github.h3yow.apigatewayjava.gateway.ratelimit.RateLimitDecision;
import com.github.h3yow.apigatewayjava.gateway.ratelimit.RateLimitReason;
import com.github.h3yow.apigatewayjava.gateway.ratelimit.RateLimiter;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 限流器单测：并发下配额守恒（不重复放行/不误拒）、固定窗口边界重置、维度独立。
 */
class RateLimiterTest {

    private static final Logger log = LoggerFactory.getLogger(RateLimiterTest.class);

    /** 可控时钟：从固定纪元起点开始，可按毫秒推进。 */
    private static MutableClock clockAt(long epochMillis) {
        return new MutableClock(epochMillis);
    }

    @Test
    void concurrentRequestsNeverExceedQuota() throws Exception {
        long quota = 50;
        long window = 1000;
        RateLimiter limiter = new RateLimiter();
        MutableClock clock = clockAt(10_000);
        QuotaKey key = new QuotaKey("premium-a", ApiVersion.V1);

        int threads = 20;
        int perThread = 10; // 共 200 个并发请求争抢 50 个配额
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicLong allowed = new AtomicLong();
        AtomicLong rejected = new AtomicLong();

        List<Future<?>> futures = new java.util.ArrayList<>();
        for (int t = 0; t < threads; t++) {
            futures.add(pool.submit(() -> {
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                for (int i = 0; i < perThread; i++) {
                    RateLimitDecision d = limiter.tryAcquire(key, quota, window, clock);
                    if (d.allowed()) {
                        allowed.incrementAndGet();
                    } else {
                        rejected.incrementAndGet();
                    }
                }
            }));
        }
        start.countDown();
        for (Future<?> f : futures) {
            f.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();

        log.info("并发限流测试 调用方=premium-a 版本=V1 配额={} 窗口={}ms 放行={} 拒绝={} 判定依据=固定窗口CAS单次自增",
                quota, window, allowed.get(), rejected.get());

        assertEquals(quota, allowed.get(), "放行数必须恰好等于配额，不能重复放行");
        assertEquals(threads * perThread - quota, rejected.get(), "其余请求必须全部被拒绝，不能误拒");
        assertEquals(threads * perThread, allowed.get() + rejected.get(), "请求总数守恒");
    }

    @Test
    void windowRollsOverAndQuotaRefreshesExactlyAtBoundary() {
        long quota = 3;
        long window = 1000;
        RateLimiter limiter = new RateLimiter();
        MutableClock clock = clockAt(5_123); // 窗口起点 5000
        QuotaKey key = new QuotaKey("legacy-b", ApiVersion.V1);

        for (long i = 1; i <= quota; i++) {
            RateLimitDecision d = limiter.tryAcquire(key, quota, window, clock);
            assertTrue(d.allowed(), "窗口内第 " + i + " 个请求应放行");
            assertEquals(quota - i, d.remaining());
        }
        RateLimitDecision rejected = limiter.tryAcquire(key, quota, window, clock);
        assertFalse(rejected.allowed());
        assertEquals(RateLimitReason.LOCAL_QUOTA_EXCEEDED, rejected.reason());
        assertEquals(6000, rejected.windowStartMillis() + window, "重试建议指向当前窗口结束时刻");
        log.info("窗口边界测试(上半窗) 调用方=legacy-b 版本=V1 配额={} 已用=3 拒绝原因={} 建议重试={}ms 判定依据=当前窗口[5000,6000)已满",
                quota, rejected.reason(), rejected.retryAfterMillis());

        // 距离边界 1ms：仍在旧窗口，必须拒绝
        clock.set(5_999);
        RateLimitDecision stillRejected = limiter.tryAcquire(key, quota, window, clock);
        assertFalse(stillRejected.allowed(), "窗口切换前 1ms 不得放行");
        log.info("窗口边界测试(临界前1ms) 调用方=legacy-b 版本=V1 拒绝原因={} 建议重试={}ms 判定依据=时间5999仍属窗口[5000,6000)",
                stillRejected.reason(), stillRejected.retryAfterMillis());

        // 恰好越过边界：新窗口计数从零开始，配额全额恢复
        clock.set(6_000);
        RateLimitDecision refreshed = limiter.tryAcquire(key, quota, window, clock);
        assertTrue(refreshed.allowed(), "窗口切换后必须放行");
        assertEquals(6_000, refreshed.windowStartMillis());
        assertEquals(quota - 1, refreshed.remaining());
        log.info("窗口边界测试(新窗口) 调用方=legacy-b 版本=V1 配额={} 剩余={} 窗口起点={} 判定依据=时间6000落入新窗口[6000,7000)，旧计数不延续",
                quota, refreshed.remaining(), refreshed.windowStartMillis());
    }

    @Test
    void quotasAreIndependentAcrossCallersAndVersions() throws Exception {
        long quota = 2;
        long window = 1000;
        RateLimiter limiter = new RateLimiter();
        MutableClock clock = clockAt(10_000);

        QuotaKey aV1 = new QuotaKey("premium-a", ApiVersion.V1);
        QuotaKey aV2 = new QuotaKey("premium-a", ApiVersion.V2);
        QuotaKey bV1 = new QuotaKey("legacy-b", ApiVersion.V1);

        consume(limiter, aV1, quota, window, clock);
        consume(limiter, aV2, quota, window, clock);

        RateLimitDecision aV1Rejected = limiter.tryAcquire(aV1, quota, window, clock);
        RateLimitDecision aV2Rejected = limiter.tryAcquire(aV2, quota, window, clock);
        RateLimitDecision bV1Allowed = limiter.tryAcquire(bV1, quota, window, clock);

        log.info("维度独立测试 调用方=premium-a 版本=V1 配额={} 判定=拒绝({}) 依据=V1窗口已满",
                quota, aV1Rejected.reason());
        log.info("维度独立测试 调用方=premium-a 版本=V2 配额={} 判定=拒绝({}) 依据=V2窗口已满，与V1独立计数",
                quota, aV2Rejected.reason());
        log.info("维度独立测试 调用方=legacy-b 版本=V1 配额={} 判定=放行 剩余={} 依据=不同调用方独立计数",
                quota, bV1Allowed.remaining());

        assertFalse(aV1Rejected.allowed());
        assertFalse(aV2Rejected.allowed());
        assertTrue(bV1Allowed.allowed(), "不同调用方共享配额维度会导致误拒");
        assertEquals(quota - 1, bV1Allowed.remaining());
    }

    private static void consume(RateLimiter limiter, QuotaKey key, long quota, long window, MutableClock clock) {
        for (long i = 0; i < quota; i++) {
            assertTrue(limiter.tryAcquire(key, quota, window, clock).allowed());
        }
    }

    /** 测试用可变固定时钟。 */
    private static final class MutableClock extends Clock {
        private volatile long millis;

        MutableClock(long millis) {
            this.millis = millis;
        }

        void set(long millis) {
            this.millis = millis;
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(millis);
        }
    }
}
