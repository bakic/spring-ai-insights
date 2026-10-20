package io.github.bakic.springaiinsights.store;

import io.github.bakic.springaiinsights.analytics.InsightsAnalytics;
import io.github.bakic.springaiinsights.analytics.InsightsAnalyticsContract;
import io.github.bakic.springaiinsights.feedback.FeedbackStore;

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
