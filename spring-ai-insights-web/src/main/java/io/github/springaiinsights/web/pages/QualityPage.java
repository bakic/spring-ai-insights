package io.github.springaiinsights.web.pages;

import java.util.List;

import io.github.springaiinsights.analytics.AbandonedConversations;
import io.github.springaiinsights.analytics.QualitySummary;
import io.github.springaiinsights.analytics.UsageSummary;
import io.github.springaiinsights.feedback.Feedback;

/** Errors, finish reasons and feedback, with the newest negative ratings and abandoned conversations. */
public record QualityPage(UsageSummary summary, QualitySummary quality, List<Feedback> recentNegative,
        AbandonedConversations abandoned) {

    public QualityPage {
        recentNegative = List.copyOf(recentNegative);
    }
}
