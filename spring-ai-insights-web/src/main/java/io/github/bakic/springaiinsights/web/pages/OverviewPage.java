package io.github.bakic.springaiinsights.web.pages;

import java.util.List;

import io.github.bakic.springaiinsights.analytics.TimeBucket;
import io.github.bakic.springaiinsights.analytics.UsageSummary;

/** The selected range, the range of the same length just before it, daily buckets and the newest turns. */
public record OverviewPage(UsageSummary current, UsageSummary previous, List<TimeBucket> days,
        List<TurnRow> recentTurns) {

    public OverviewPage {
        days = List.copyOf(days);
        recentTurns = List.copyOf(recentTurns);
    }
}
