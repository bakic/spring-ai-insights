package io.github.bakic.springaiinsights.observability;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SampleScrapeTest {

    @Test
    void exportedNamesArePinned() {
        assertThat(SampleScrape.metricNames()).containsExactlyInAnyOrder(
                "ai_insights_turns_total", "ai_insights_tokens_total", "ai_insights_cost_total",
                "ai_insights_tool_calls_total", "ai_insights_retrievals_total", "ai_insights_feedback_total",
                "ai_insights_events_dropped_total", "ai_insights_store_errors_total",
                "ai_insights_turns_stalled_total", "ai_insights_internal_errors_total",
                "ai_insights_unpriced_models_total", "ai_insights_ttft_seconds_bucket",
                "ai_insights_ttft_seconds_count", "ai_insights_ttft_seconds_sum", "ai_insights_ttft_seconds_max");
    }

    @Test
    void metricNamesInFindsEveryIdentifier() {
        assertThat(SampleScrape.metricNamesIn(
                "sum(rate(ai_insights_turns_total{application=~\"$application\"}[5m])) / ai_insights_cost_total"))
            .containsExactlyInAnyOrder("ai_insights_turns_total", "ai_insights_cost_total");
    }
}
