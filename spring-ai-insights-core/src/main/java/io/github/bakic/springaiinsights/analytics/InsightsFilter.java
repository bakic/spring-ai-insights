package io.github.bakic.springaiinsights.analytics;

import java.time.Instant;
import java.time.ZoneId;
import java.util.Objects;

import org.jspecify.annotations.Nullable;

/**
 * Selects turns started in {@code [from, to)}. A null {@code application} selects all applications; a null
 * {@code tenantId} selects all tenants. {@code zone} defines the hour and day buckets of a timeline.
 */
public record InsightsFilter(@Nullable String application, Instant from, Instant to, @Nullable String tenantId,
        ZoneId zone) {

    public InsightsFilter {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        Objects.requireNonNull(zone, "zone");
        if (!from.isBefore(to)) {
            throw new IllegalArgumentException("from must be before to: " + from + " / " + to);
        }
    }
}
