package io.github.springaiinsights.model;

import org.jspecify.annotations.Nullable;

/** One tool call within a turn. {@code repeatIndex} counts earlier calls to the same tool in the turn. */
public record ToolCallRecord(String name, long durationMs, ToolOutcome outcome,
        @Nullable String errorType, int repeatIndex) {
}
