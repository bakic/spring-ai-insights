package io.github.springaiinsights.store;

import io.github.springaiinsights.analytics.InsightsAnalytics;
import io.github.springaiinsights.analytics.InsightsAnalyticsContract;
import io.github.springaiinsights.feedback.FeedbackStore;

class InMemoryAnalyticsContractTest extends InsightsAnalyticsContract {

    private final InMemoryEventStore store = new InMemoryEventStore(1000);

    @Override
    protected InsightsEventStore store() {
        return store;
    }

    @Override
    protected FeedbackStore feedbackStore() {
        return store;
    }

    @Override
    protected InsightsAnalytics analytics() {
        return store;
    }
}
