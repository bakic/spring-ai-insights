package io.github.springaiinsights.report;

import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** A {@link ReportLock} for one instance. Expired claims are removed when another key is claimed. */
public final class InMemoryReportLock implements ReportLock {

    private final ConcurrentMap<String, Instant> claims = new ConcurrentHashMap<>();

    private final Clock clock;

    public InMemoryReportLock() {
        this(Clock.systemUTC());
    }

    public InMemoryReportLock(Clock clock) {
        this.clock = clock;
    }

    @Override
    public boolean claim(String key, Instant expiresAt) {
        Instant now = clock.instant();
        claims.values().removeIf(expiry -> !expiry.isAfter(now));
        AtomicBoolean claimed = new AtomicBoolean();
        claims.compute(key, (k, current) -> {
            if (current != null && current.isAfter(now)) {
                return current;
            }
            claimed.set(true);
            return expiresAt;
        });
        return claimed.get();
    }
}
