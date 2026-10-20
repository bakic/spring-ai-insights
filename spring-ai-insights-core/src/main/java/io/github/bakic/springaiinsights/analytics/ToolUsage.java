package io.github.bakic.springaiinsights.analytics;

/**
 * Usage of one tool. {@code turns} counts the turns that called it; {@code p95DurationMs} is the nearest-rank 95th
 * percentile of call durations; {@code repeatedCalls} counts calls of a tool the same turn already called, whatever
 * the arguments.
 */
public record ToolUsage(String tool, long calls, long errors, long turns, double avgDurationMs, long p95DurationMs,
        long repeatedCalls) {

    /** Index of the nearest-rank 95th percentile in {@code count} ascending values. */
    public static int p95Index(long count) {
        return (int) Math.max(0, (long) Math.ceil(0.95 * count) - 1);
    }
}
