package io.github.springaiinsights.web.pages;

import java.util.List;

import io.github.springaiinsights.analytics.QualitySummary;
import io.github.springaiinsights.analytics.UsageSummary;
import io.github.springaiinsights.feedback.Feedback;

/** Errors, finish reasons and feedback, with the newest negative ratings. */
public record QualityPage(UsageSummary summary, QualitySummary quality, List<Feedback> recentNegative) {

    public QualityPage {
        recentNegative = List.copyOf(recentNegative);
    }
}
