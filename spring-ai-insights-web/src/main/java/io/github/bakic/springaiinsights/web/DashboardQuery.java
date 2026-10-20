package io.github.bakic.springaiinsights.web;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import io.github.bakic.springaiinsights.analytics.InsightsFilter;
import org.jspecify.annotations.Nullable;

import org.springframework.web.servlet.function.ServerRequest;

/**
 * Reads the dashboard filter from {@code app}, {@code from}, {@code to}, {@code tenant} and {@code zone}. Invalid
 * values throw {@link IllegalArgumentException} with a message that is safe to return to the caller.
 */
public final class DashboardQuery {

    static final Duration DEFAULT_RANGE = Duration.ofDays(7);

    static final Duration MAX_RANGE = Duration.ofDays(366);

    // Keeps date arithmetic (previous period, month boundaries) and store conversions away from overflow.
    private static final Instant EARLIEST = Instant.parse("0000-01-01T00:00:00Z");

    private static final Instant LATEST = Instant.parse("9999-12-31T23:59:59Z");

    private DashboardQuery() {
    }

    public static InsightsFilter parse(ServerRequest request, String defaultApplication, Clock clock) {
        String application = param(request, "app");
        String tenant = param(request, "tenant");
        ZoneId zone = zone(param(request, "zone"));
        String fromParam = param(request, "from");
        String toParam = param(request, "to");
        Instant to = toParam == null ? clock.instant() : instant("to", toParam);
        Instant from = fromParam == null ? to.minus(DEFAULT_RANGE) : instant("from", fromParam);
        if (!from.isBefore(to)) {
            throw new IllegalArgumentException("from must be before to");
        }
        if (Duration.between(from, to).compareTo(MAX_RANGE) > 0) {
            throw new IllegalArgumentException("range must not exceed 366 days");
        }
        return new InsightsFilter(application == null ? defaultApplication : application, from, to, tenant, zone);
    }

    static @Nullable String param(ServerRequest request, String name) {
        return request.param(name).map(String::strip).filter(value -> !value.isEmpty()).orElse(null);
    }

    private static ZoneId zone(@Nullable String value) {
        if (value == null) {
            return ZoneOffset.UTC;
        }
        try {
            return ZoneId.of(value);
        }
        catch (DateTimeException e) {
            throw new IllegalArgumentException("invalid zone: " + value);
        }
    }

    private static Instant instant(String name, String value) {
        try {
            Instant instant = Instant.parse(value);
            if (instant.isBefore(EARLIEST) || instant.isAfter(LATEST)) {
                throw new IllegalArgumentException("invalid " + name + ": " + value);
            }
            return instant;
        }
        catch (DateTimeException e) {
            throw new IllegalArgumentException("invalid " + name + ": " + value);
        }
    }
}
