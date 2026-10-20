package io.github.bakic.springaiinsights.autoconfigure;

import io.github.bakic.springaiinsights.mongodb.MongoEventStore;
import io.github.bakic.springaiinsights.store.InsightsEventStore;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnSingleCandidate;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.data.mongodb.core.MongoOperations;

/** Stores turns in the application's MongoDB when Spring Data MongoDB is configured and the store is not forced to memory. */
@AutoConfiguration(afterName = "org.springframework.boot.data.mongodb.autoconfigure.DataMongoAutoConfiguration",
        before = InsightsAutoConfiguration.class)
@ConditionalOnClass(name = { "org.springframework.data.mongodb.core.MongoOperations",
        "io.github.bakic.springaiinsights.mongodb.MongoEventStore" })
@ConditionalOnBooleanProperty(name = "ai-insights.enabled", matchIfMissing = true)
@Conditional(MongoStoreCondition.class)
@EnableConfigurationProperties(InsightsProperties.class)
public class InsightsMongoAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(InsightsEventStore.class)
    @ConditionalOnSingleCandidate(MongoOperations.class)
    MongoEventStore insightsMongoEventStore(MongoOperations mongo, InsightsProperties properties) {
        return new MongoEventStore(mongo, properties.getMongodb().getCollectionPrefix(), properties.getRetention());
    }
}
