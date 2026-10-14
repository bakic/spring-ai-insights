package io.github.springaiinsights.analytics;

import java.math.BigDecimal;
import java.time.Instant;

/** Usage of one hour or day; {@code start} is the start of the bucket in the filter's time zone. */
public record TimeBucket(Instant start, long turns, long conversations, long activeUsers, BigDecimal cost) {
}
