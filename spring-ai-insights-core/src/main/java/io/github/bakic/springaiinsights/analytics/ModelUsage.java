package io.github.bakic.springaiinsights.analytics;

import java.math.BigDecimal;

/** Usage of one model; {@code cost} sums its priced calls, {@code unpricedCalls} counts the others. */
public record ModelUsage(String model, long calls, long inputTokens, long outputTokens, long cacheReadTokens,
        long cacheWriteTokens, BigDecimal cost, long unpricedCalls) {
}
