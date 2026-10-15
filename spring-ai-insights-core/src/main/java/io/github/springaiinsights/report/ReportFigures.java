package io.github.springaiinsights.report;

import java.math.BigDecimal;
import java.math.RoundingMode;

import io.github.springaiinsights.analytics.QualitySummary;
import io.github.springaiinsights.analytics.UsageSummary;
import org.jspecify.annotations.Nullable;

/**
 * The figures of one week. Rates are between 0 and 1, and null when there is nothing to divide by: no conversation,
 * no feedback or no knowledge base query.
 *
 * @param unpricedTurns turns whose cost is missing from {@code cost} because a model has no price
 */
public record ReportFigures(long activeUsers, long conversations, long turns, BigDecimal cost,
        @Nullable BigDecimal costPerConversation, @Nullable Double positiveFeedbackRate,
        @Nullable Double emptyRetrievalRate, long unpricedTurns) {

    public static ReportFigures of(UsageSummary usage, QualitySummary quality) {
        long rated = quality.positiveFeedback() + quality.negativeFeedback();
        return new ReportFigures(usage.activeUsers(), usage.conversations(), usage.turns(), usage.cost(),
                usage.conversations() == 0 ? null
                        : usage.cost().divide(BigDecimal.valueOf(usage.conversations()), 4, RoundingMode.HALF_UP),
                rated == 0 ? null : (double) quality.positiveFeedback() / rated,
                usage.retrievals() == 0 ? null : (double) usage.emptyRetrievals() / usage.retrievals(),
                usage.unpricedTurns());
    }
}
