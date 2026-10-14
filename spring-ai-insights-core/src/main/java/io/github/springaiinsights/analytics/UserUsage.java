package io.github.springaiinsights.analytics;

import java.math.BigDecimal;
import java.time.Instant;

/** Usage of one (pseudonymised) user; {@code lastSeen} is the start of the user's latest turn. */
public record UserUsage(String userId, long turns, long conversations, BigDecimal cost, Instant lastSeen) {
}
