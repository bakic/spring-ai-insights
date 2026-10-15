package io.github.springaiinsights.analytics;

import java.util.List;

/**
 * Answer quality over a filter. Feedback is selected by its creation time. {@code finishReasons} counts turns per
 * distinct provider finish reason, as reported by the provider; {@code errorTypes} counts failed turns per exception
 * type. Both are sorted by count, highest first, then by key.
 */
public record QualitySummary(long positiveFeedback, long negativeFeedback, List<KeyCount> finishReasons,
        List<KeyCount> errorTypes) {

    public QualitySummary {
        finishReasons = List.copyOf(finishReasons);
        errorTypes = List.copyOf(errorTypes);
    }
}
