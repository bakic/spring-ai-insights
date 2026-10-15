package io.github.springaiinsights.report;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryReportLockTest extends ReportLockContract {

    private Instant now = Instant.parse("2026-10-19T06:00:00Z");

    private final Clock clock = new Clock() {
        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    };

    @Override
    protected ReportLock lock() {
        return new InMemoryReportLock(clock);
    }

    @Override
    protected Instant expiry() {
        return now.plus(Duration.ofDays(35));
    }

    @Test
    void expiredClaimCanBeTakenAgain() {
        ReportLock lock = lock();
        assertThat(lock.claim("k", now.plusSeconds(60))).isTrue();
        now = now.plusSeconds(60);
        assertThat(lock.claim("k", now.plusSeconds(60))).isTrue();
        assertThat(lock.claim("k", now.plusSeconds(60))).isFalse();
    }
}
