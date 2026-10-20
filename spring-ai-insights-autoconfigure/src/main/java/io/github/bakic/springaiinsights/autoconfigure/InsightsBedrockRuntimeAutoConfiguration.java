package io.github.bakic.springaiinsights.autoconfigure;

import io.github.bakic.springaiinsights.bedrock.ObservedBedrockAgentRuntime;
import io.micrometer.observation.ObservationRegistry;
import software.amazon.awssdk.services.bedrockagentruntime.BedrockAgentRuntimeClient;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;

/**
 * Observes the Amazon Bedrock knowledge base searches an application makes with its own
 * {@link BedrockAgentRuntimeClient} beans (see {@link ObservedBedrockAgentRuntime}), so they are recorded like
 * Spring AI vector store searches. Turned off with {@code ai-insights.knowledge.observe-stores=false}.
 */
@AutoConfiguration
@ConditionalOnClass(name = { "io.github.bakic.springaiinsights.bedrock.ObservedBedrockAgentRuntime",
        "software.amazon.awssdk.services.bedrockagentruntime.BedrockAgentRuntimeClient" })
@ConditionalOnBooleanProperty(name = "ai-insights.enabled", matchIfMissing = true)
@ConditionalOnBooleanProperty(name = "ai-insights.knowledge.observe-stores", matchIfMissing = true)
public class InsightsBedrockRuntimeAutoConfiguration {

    @Bean
    static BeanPostProcessor insightsObservingBedrockAgentRuntimePostProcessor(
            ObjectProvider<ObservationRegistry> observationRegistry) {
        return new BeanPostProcessor() {

            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (bean instanceof BedrockAgentRuntimeClient client) {
                    return ObservedBedrockAgentRuntime.wrap(client,
                            observationRegistry.getIfUnique(() -> ObservationRegistry.NOOP));
                }
                return bean;
            }
        };
    }
}
