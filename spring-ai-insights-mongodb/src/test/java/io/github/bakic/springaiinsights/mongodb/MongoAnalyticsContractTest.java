package io.github.bakic.springaiinsights.mongodb;

import java.time.Duration;

import io.github.bakic.springaiinsights.analytics.InsightsAnalytics;
import io.github.bakic.springaiinsights.analytics.InsightsAnalyticsContract;
import io.github.bakic.springaiinsights.feedback.FeedbackStore;
import io.github.bakic.springaiinsights.store.InsightsEventStore;

class MongoAnalyticsContractTest extends InsightsAnalyticsContract {

    private final MongoEventStore store = new MongoEventStore(MongoTestSupport.freshTemplate(), "ai_insights_",
            Duration.ofDays(90));

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
