package io.github.springaiinsights.analytics;

import java.math.BigDecimal;

import org.jspecify.annotations.Nullable;

/**
 * Totals over the turns selected by a filter. {@code cost} sums the priced model calls; {@code unpricedTurns} counts
 * turns with at least one model call without a price. {@code avgTimeToFirstTokenMs} is null when no turn has one.
 */
public record UsageSummary(long turns, long conversations, long activeUsers, long anonymousTurns, long errorTurns,
        long cancelledTurns, long inputTokens, long outputTokens, long cacheReadTokens, long cacheWriteTokens,
        BigDecimal cost, long unpricedTurns, double avgLatencyMs, @Nullable Double avgTimeToFirstTokenMs,
        long toolCalls, long retrievals, long emptyRetrievals) {
}
