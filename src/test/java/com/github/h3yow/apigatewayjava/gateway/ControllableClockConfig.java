package com.github.h3yow.apigatewayjava.gateway;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 测试专用可控时钟：替换生产系统时钟，让固定窗口边界可被测试精确驱动，
 * 不依赖真实等待。默认时刻为一个远离 0 的固定基准，且可随时手动推进。
 */
@TestConfiguration
public class ControllableClockConfig {

    public static final class MutableClock extends Clock {
        private final AtomicLong millis = new AtomicLong(1_000_000_000_000L);

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
            return Instant.ofEpochMilli(millis.get());
        }

        public long millis() {
            return millis.get();
        }

        public void setMillis(long value) {
            millis.set(value);
        }

        /** 绝对推进：切到一个全新窗口的起点（与当前值无关），保证计数归零可重复。 */
        public void jumpToFreshWindow(long windowMillis) {
            long base = millis.get();
            long nextStart = ((base / windowMillis) + 1) * windowMillis;
            millis.set(nextStart);
        }
    }

    @Bean
    @Primary
    public MutableClock mutableClock() {
        return new MutableClock();
    }
}
