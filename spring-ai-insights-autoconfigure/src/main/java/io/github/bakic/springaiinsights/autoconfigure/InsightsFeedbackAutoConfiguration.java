package io.github.bakic.springaiinsights.autoconfigure;

import java.time.Clock;

import io.github.bakic.springaiinsights.metrics.InsightsMetrics;
import io.github.bakic.springaiinsights.feedback.FeedbackStore;
import io.github.bakic.springaiinsights.feedback.InsightsFeedback;
import io.github.bakic.springaiinsights.identity.Pseudonymiser;
import io.github.bakic.springaiinsights.store.AsyncEventWriter;
import io.github.bakic.springaiinsights.store.InsightsEventStore;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/** Lets users rate assistant answers when the event store also stores feedback. */
@AutoConfiguration(after = { InsightsAutoConfiguration.class, InsightsMongoAutoConfiguration.class })
@ConditionalOnBean({ FeedbackStore.class, AsyncEventWriter.class, InsightsMetrics.class })
public class InsightsFeedbackAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    InsightsFeedback insightsFeedback(InsightsEventStore turns, FeedbackStore feedback, AsyncEventWriter writer,
            ObjectProvider<Pseudonymiser> pseudonymiser, InsightsMetrics metrics) {
        return new InsightsFeedback(turns, feedback, writer, pseudonymiser.getIfAvailable(), metrics,
                Clock.systemUTC());
    }
}
