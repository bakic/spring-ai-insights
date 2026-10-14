package io.github.springaiinsights.analytics;

/**
 * Usage of one tool. {@code turns} counts the turns that called it; {@code repeatedCalls} counts calls repeating the
 * same tool with the same arguments in a turn.
 */
public record ToolUsage(String tool, long calls, long errors, long turns, double avgDurationMs, long repeatedCalls) {
}
