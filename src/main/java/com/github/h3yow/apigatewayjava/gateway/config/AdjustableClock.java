package com.github.h3yow.apigatewayjava.gateway.config;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 可冻结/可推进的时钟。
 *
 * <p>默认行为与 {@link Clock#systemUTC()} 完全一致（跟随真实系统时间），
 * 因此生产装配下行为不变；测试可注入同一个 Bean，调用 {@link #freezeAt(long)}
 * 把时间固定在窗口内的任意位置、用 {@link #advanceMillis(long)} 主动把时间推进到
 * 窗口边界，从而在不真实等待、不受机器负载影响的前提下确定性地验证窗口语义。</p>
 *
 * <p>线程安全：冻结值放在 {@link AtomicLong} 中，并发请求读取到的都是同一个
 * 被冻结的瞬时，不会因为“一批请求恰好跨过整秒”而出现放行数漂移。</p>
 */
public class AdjustableClock extends Clock {

    /** 哨兵值：未冻结，跟随系统时间。 */
    private static final long FOLLOW_SYSTEM = Long.MIN_VALUE;

    private final Clock delegate = Clock.systemUTC();
    private final AtomicLong frozenMillis = new AtomicLong(FOLLOW_SYSTEM);

    /** 冻结在指定 epoch 毫秒，之后 {@link #millis()} 恒返回该值直到再次调整。 */
    public void freezeAt(long epochMillis) {
        frozenMillis.set(epochMillis);
    }

    /**
     * 在当前基准上前进 {@code delta} 毫秒。
     * 若尚未冻结，则以当前系统时间为基准冻结后再前进。
     */
    public void advanceMillis(long delta) {
        frozenMillis.getAndUpdate(v -> (v == FOLLOW_SYSTEM ? delegate.millis() : v) + delta);
    }

    /** 解除冻结，恢复跟随系统时间。 */
    public void followSystem() {
        frozenMillis.set(FOLLOW_SYSTEM);
    }

    @Override
    public long millis() {
        long v = frozenMillis.get();
        return v == FOLLOW_SYSTEM ? delegate.millis() : v;
    }

    @Override
    public Instant instant() {
        return Instant.ofEpochMilli(millis());
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        throw new UnsupportedOperationException("本地网关只使用 UTC");
    }
}
