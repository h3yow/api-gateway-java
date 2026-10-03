package com.github.h3yow.apigatewayjava.gateway;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * 测试专用可控时钟：时间完全由测试代码推进，不随真实时间流动。
 *
 * 用途：
 * <ul>
 *     <li>把限流窗口边界“主动推进”到临界点（如恰好 windowEnd-1 / windowEnd），
 *         而不是真实等待；</li>
 *     <li>冻结时间，使并发压测全程落在同一窗口内，放行数严格等于配额，
 *         与机器负载、执行时刻无关；</li>
 *     <li>同一进程内可反复复现同一结论。</li>
 * </ul>
 * 线程安全：毫秒值为 volatile，多线程并发读到的都是同一个逻辑时刻。
 */
public final class MutableClock extends Clock {

    private volatile long millis;

    public MutableClock(long initialMillis) {
        this.millis = initialMillis;
    }

    /** 直接设定当前逻辑时刻（毫秒）。 */
    public void setMillis(long millis) {
        this.millis = millis;
    }

    /** 前进指定毫秒数。 */
    public void advanceMillis(long delta) {
        this.millis += delta;
    }

    public long millisNow() {
        return millis;
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

    @Override
    public long millis() {
        return millis;
    }
}
