package com.github.h3yow.apigatewayjava.gateway.ratelimit;

import com.github.h3yow.apigatewayjava.gateway.group.ApiGroupConfig;
import com.github.h3yow.apigatewayjava.gateway.group.GroupQuotaResolver;
import com.github.h3yow.apigatewayjava.gateway.model.ApiVersion;
import com.github.h3yow.apigatewayjava.gateway.model.QuotaPolicy;
import com.github.h3yow.apigatewayjava.gateway.model.RateLimitDecision;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
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
 * 接口组维度限流单测：组间配额隔离、组配额优先/回退、并发与窗口边界守恒。
 * 全程使用 {@link MutableClock}，窗口边界由测试主动推进，任意时间稳定复现，不真实等待。
 */
class GroupRateLimiterTest {

    private static final ApiVersion V10 = new ApiVersion(1, 0);

    static RateLimiter limiter(ApiGroupConfig groups, RateLimitConfig callers, MutableClock clock) {
        GroupQuotaResolver resolver = new GroupQuotaResolver(groups, callers, java.util.Map.of());
        return new RateLimiter(resolver, clock);
    }

    static RateLimitConfig callerConfig() {
        var map = new java.util.HashMap<String, QuotaPolicy>();
        map.put(RateLimitConfig.key(RateLimitConfig.DEFAULT_CALLER, RateLimitConfig.ANY_VERSION),
                new QuotaPolicy(1, 1_000));
        map.put(RateLimitConfig.key("caller-a", "v1.0"), new QuotaPolicy(5, 1_000));
        return new RateLimitConfig(map);
    }

    @Test
    void quotasAreIsolatedAcrossGroups_sameCallerSameVersion() {
        MutableClock clock = new MutableClock(0);
        ApiGroupConfig groups = ApiGroupConfig.defaultLocalConfig(
                java.util.List.of(V10, new ApiVersion(1, 1)));
        RateLimiter rl = limiter(groups, callerConfig(), clock);

        // orders 组配额 2/s：打满
        assertTrue(rl.tryAcquire("caller-a", V10, "orders").acquired());
        assertTrue(rl.tryAcquire("caller-a", V10, "orders").acquired());
        RateLimitDecision ordersReject = rl.tryAcquire("caller-a", V10, "orders");
        assertFalse(ordersReject.acquired());
        assertEquals(2, ordersReject.limit());
        assertTrue(ordersReject.basis().contains("group quota for group=orders"));
        System.out.printf("[GroupRateLimitTest] orders 组打满: %s%n", ordersReject.basis());

        // 同调用方同版本访问 payments 组（配额 4/s）与 default 组（回退到 caller-a v1.0=5/s）互不占用
        for (int i = 0; i < 4; i++) {
            assertTrue(rl.tryAcquire("caller-a", V10, "payments").acquired(), "payments 组额度独立");
        }
        assertFalse(rl.tryAcquire("caller-a", V10, "payments").acquired());

        for (int i = 0; i < 5; i++) {
            assertTrue(rl.tryAcquire("caller-a", V10, ApiGroupConfig.DEFAULT_GROUP_ID).acquired(),
                    "default 组回退调用方×版本配额，独立计数");
        }
        RateLimitDecision defaultReject =
                rl.tryAcquire("caller-a", V10, ApiGroupConfig.DEFAULT_GROUP_ID);
        assertFalse(defaultReject.acquired());
        assertTrue(defaultReject.basis().contains("回退到调用方×版本策略"));

        // orders 计数仍是 2，未被其他组影响
        assertEquals(2, rl.currentUsed("caller-a", V10, "orders"));
        assertEquals(4, rl.currentUsed("caller-a", V10, "payments"));
        assertEquals(5, rl.currentUsed("caller-a", V10, ApiGroupConfig.DEFAULT_GROUP_ID));
        System.out.println("[GroupRateLimitTest] 组间隔离 orders=2 payments=4 default=5 互不占用");
    }

    @Test
    void unconfiguredGroupQuota_fallsBackNeverUnlimited() {
        MutableClock clock = new MutableClock(0);
        ApiGroupConfig groups = ApiGroupConfig.defaultLocalConfig(
                java.util.List.of(V10, new ApiVersion(1, 1)));
        RateLimiter rl = limiter(groups, callerConfig(), clock);

        // reports 组无组配额：caller-a v1.0 回退到 5/s（非无限）
        for (int i = 0; i < 5; i++) {
            assertTrue(rl.tryAcquire("caller-a", V10, "reports").acquired());
        }
        assertFalse(rl.tryAcquire("caller-a", V10, "reports").acquired(),
                "无组配额也必须回退到有限配额，不能无限放行");

        // 未登记调用方回退到默认 1/s
        assertTrue(rl.tryAcquire("stranger", V10, "reports").acquired());
        assertFalse(rl.tryAcquire("stranger", V10, "reports").acquired());
        System.out.println("[GroupRateLimitTest] 无组配额回退有限策略，未登记调用方 1/s 第二请求即拒");
    }

    @Test
    void groupWindowsCanDiffer_andRolloverIsAccurate() {
        MutableClock clock = new MutableClock(999);
        ApiGroupConfig groups = ApiGroupConfig.defaultLocalConfig(
                java.util.List.of(V10, new ApiVersion(1, 1)));
        RateLimiter rl = limiter(groups, callerConfig(), clock);

        // orders 窗口 1000ms：在 [0,1000) 打满 2 个
        assertTrue(rl.tryAcquire("caller-a", V10, "orders").acquired());
        RateLimitDecision second = rl.tryAcquire("caller-a", V10, "orders");
        assertTrue(second.acquired());
        RateLimitDecision reject = rl.tryAcquire("caller-a", V10, "orders");
        assertFalse(reject.acquired());
        assertEquals(1L, reject.retryAfterMillis(), "距 orders 窗口结束 1ms");
        assertEquals(1_000L, reject.windowEnd());

        // 推进到新窗口边界：计数归零
        clock.setMillis(1_000);
        RateLimitDecision next = rl.tryAcquire("caller-a", V10, "orders");
        assertTrue(next.acquired());
        assertEquals(1L, next.used());
        assertEquals(1_000L, next.windowStart());
        assertEquals(2_000L, next.windowEnd());
        System.out.printf("[GroupRateLimitTest] 组窗口翻页精确 used=%d [%d,%d)%n",
                next.used(), next.windowStart(), next.windowEnd());
    }

    @Test
    void concurrentGroupAcquire_quotaConserved() throws Exception {
        MutableClock clock = new MutableClock(5_000);
        ApiGroupConfig groups = ApiGroupConfig.defaultLocalConfig(
                java.util.List.of(V10, new ApiVersion(1, 1)));
        RateLimiter rl = limiter(groups, callerConfig(), clock);

        int threads = 16;
        int perThread = 4; // 64 请求抢 orders 组 2 个配额
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger granted = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        ConcurrentLinkedQueue<Long> usages = new ConcurrentLinkedQueue<>();

        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        RateLimitDecision d = rl.tryAcquire("caller-a", V10, "orders");
                        if (d.acquired()) {
                            granted.incrementAndGet();
                            usages.add(d.used());
                        } else {
                            rejected.incrementAndGet();
                            assertEquals(2, d.used());
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

        assertEquals(2, granted.get(), "orders 组并发下恰好放行 2");
        assertEquals(threads * perThread - 2, rejected.get());
        var sorted = usages.stream().sorted().toList();
        assertEquals(java.util.List.of(1L, 2L), sorted, "放行序号严格 1..2，无重复扣减");
        assertEquals(2, rl.currentUsed("caller-a", V10, "orders"));
        System.out.printf("[GroupRateLimitTest] orders 并发守恒 granted=%d rejected=%d%n",
                granted.get(), rejected.get());
    }

    @Test
    void concurrentGroupAcquire_repeatableAcrossManyRoundsInOneJvm() throws Exception {
        // 同一进程内连续 25 轮：每轮 64 并发抢 orders 组 2 个配额，
        // 同时验证同调用方同版本在 payments 组的额度始终不被占用。
        int rounds = 25;
        for (int round = 1; round <= rounds; round++) {
            MutableClock clock = new MutableClock(5_000);
            ApiGroupConfig groups = ApiGroupConfig.defaultLocalConfig(
                    java.util.List.of(V10, new ApiVersion(1, 1)));
            RateLimiter rl = limiter(groups, callerConfig(), clock);

            int threads = 16;
            int perThread = 4;
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(threads);
            AtomicInteger granted = new AtomicInteger();
            AtomicInteger rejected = new AtomicInteger();
            ConcurrentLinkedQueue<Long> usages = new ConcurrentLinkedQueue<>();

            for (int t = 0; t < threads; t++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        for (int i = 0; i < perThread; i++) {
                            RateLimitDecision d = rl.tryAcquire("caller-a", V10, "orders");
                            if (d.acquired()) {
                                granted.incrementAndGet();
                                usages.add(d.used());
                            } else {
                                rejected.incrementAndGet();
                                assertEquals(2, d.used(), "被拒绝时 used 必须停在 2");
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

            assertEquals(2, granted.get(), "第 " + round + " 轮 orders 组恰好放行 2");
            assertEquals(threads * perThread - 2, rejected.get());
            assertEquals(java.util.List.of(1L, 2L), usages.stream().sorted().toList(),
                    "第 " + round + " 轮放行序号严格 1..2");
            assertEquals(2, rl.currentUsed("caller-a", V10, "orders"));
            // 跨组隔离：orders 被打满，payments 组额度仍是满的 4
            for (int i = 0; i < 4; i++) {
                assertTrue(rl.tryAcquire("caller-a", V10, "payments").acquired(),
                        "第 " + round + " 轮 payments 组额度不受 orders 影响");
            }
            assertFalse(rl.tryAcquire("caller-a", V10, "payments").acquired());
        }
        System.out.printf("[GroupRateLimitTest] 单进程连续 %d 轮组维度并发守恒+跨组隔离结论一致%n", rounds);
    }

    /** 可控时钟，毫秒精度（与 RateLimiterTest 中一致的本地可复现手段）。 */
    static final class MutableClock extends Clock {
        private volatile long millis;

        MutableClock(long millis) {
            this.millis = millis;
        }

        void setMillis(long millis) {
            this.millis = millis;
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(millis);
        }
    }
}
