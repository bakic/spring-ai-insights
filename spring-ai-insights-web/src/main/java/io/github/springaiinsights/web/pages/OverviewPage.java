package io.github.springaiinsights.web.pages;

import java.util.List;

import io.github.springaiinsights.analytics.TimeBucket;
import io.github.springaiinsights.analytics.UsageSummary;

/** The selected range, the range of the same length just before it, daily buckets and the newest turns. */
public record OverviewPage(UsageSummary current, UsageSummary previous, List<TimeBucket> days,
        List<TurnRow> recentTurns) {

    public OverviewPage {
        days = List.copyOf(days);
        recentTurns = List.copyOf(recentTurns);
    }
}
