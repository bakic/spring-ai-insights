package io.github.bakic.springaiinsights.web.pages;

import java.util.List;

import io.github.bakic.springaiinsights.analytics.AbandonedConversations;
import io.github.bakic.springaiinsights.analytics.QualitySummary;
import io.github.bakic.springaiinsights.analytics.UsageSummary;
import io.github.bakic.springaiinsights.feedback.Feedback;

/** Errors, finish reasons and feedback, with the newest negative ratings and abandoned conversations. */
public record QualityPage(UsageSummary summary, QualitySummary quality, List<Feedback> recentNegative,
        AbandonedConversations abandoned) {

    public QualityPage {
        recentNegative = List.copyOf(recentNegative);
    }
}
