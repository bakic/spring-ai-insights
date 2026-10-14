package io.github.springaiinsights.autoconfigure;

import org.springframework.boot.autoconfigure.condition.ConditionOutcome;
import org.springframework.boot.autoconfigure.condition.SpringBootCondition;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/** Matches unless {@code ai-insights.store} is {@code memory}. */
class MongoStoreCondition extends SpringBootCondition {

    @Override
    public ConditionOutcome getMatchOutcome(ConditionContext context, AnnotatedTypeMetadata metadata) {
        InsightsProperties.StoreType store = Binder.get(context.getEnvironment())
            .bind(InsightsProperties.PREFIX + ".store", InsightsProperties.StoreType.class)
            .orElse(InsightsProperties.StoreType.AUTO);
        return store == InsightsProperties.StoreType.MEMORY ? ConditionOutcome.noMatch("ai-insights.store is memory")
                : ConditionOutcome.match("ai-insights.store is " + store.name().toLowerCase());
    }
}
