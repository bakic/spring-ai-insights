package io.github.springaiinsights.model;

import java.math.BigDecimal;

import org.jspecify.annotations.Nullable;

/** One call to a chat model within a turn. {@code cost} is null when the model has no configured price. */
public record ModelCall(String provider, String model, long inputTokens, long outputTokens,
        long cacheReadTokens, long cacheWriteTokens, long durationMs,
        @Nullable BigDecimal cost, @Nullable String errorType) {
}
