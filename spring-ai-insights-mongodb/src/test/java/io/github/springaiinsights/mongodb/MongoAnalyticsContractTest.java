package io.github.springaiinsights.mongodb;

import java.time.Duration;

import io.github.springaiinsights.analytics.InsightsAnalytics;
import io.github.springaiinsights.analytics.InsightsAnalyticsContract;
import io.github.springaiinsights.feedback.FeedbackStore;
import io.github.springaiinsights.store.InsightsEventStore;

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
