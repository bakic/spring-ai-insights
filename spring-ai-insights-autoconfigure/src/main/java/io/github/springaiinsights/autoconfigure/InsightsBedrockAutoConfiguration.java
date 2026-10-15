package io.github.springaiinsights.autoconfigure;

import io.github.springaiinsights.bedrock.BedrockKnowledgeBaseCatalog;
import org.jspecify.annotations.Nullable;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.bedrockagent.BedrockAgentClient;
import software.amazon.awssdk.services.bedrockagent.BedrockAgentClientBuilder;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionOutcome;
import org.springframework.boot.autoconfigure.condition.SpringBootCondition;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Lists the documents of the configured Amazon Bedrock Knowledge Bases, so the dashboard shows those never retrieved.
 * Uses the application's {@link BedrockAgentClient} bean if there is exactly one, else builds (and closes) its own.
 */
@AutoConfiguration
@ConditionalOnClass(name = { "io.github.springaiinsights.bedrock.BedrockKnowledgeBaseCatalog",
        "software.amazon.awssdk.services.bedrockagent.BedrockAgentClient" })
@ConditionalOnBooleanProperty(name = "ai-insights.enabled", matchIfMissing = true)
@Conditional(InsightsBedrockAutoConfiguration.KnowledgeBasesConfigured.class)
@EnableConfigurationProperties(InsightsProperties.class)
public class InsightsBedrockAutoConfiguration implements DisposableBean {

    private @Nullable BedrockAgentClient ownClient;

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    BedrockKnowledgeBaseCatalog insightsBedrockKnowledgeBaseCatalog(ObjectProvider<BedrockAgentClient> clients,
            InsightsProperties properties) {
        InsightsProperties.Knowledge.Bedrock bedrock = properties.getKnowledge().getBedrock();
        BedrockAgentClient client = clients.getIfUnique(() -> ownClient(bedrock.getRegion()));
        BedrockKnowledgeBaseCatalog catalog = new BedrockKnowledgeBaseCatalog(client,
                bedrock.getKnowledgeBaseIds().stream().filter(id -> !id.isBlank()).map(String::strip).toList(),
                bedrock.getRefresh());
        catalog.start();
        return catalog;
    }

    private synchronized BedrockAgentClient ownClient(@Nullable String region) {
        BedrockAgentClientBuilder builder = BedrockAgentClient.builder();
        if (region != null && !region.isBlank()) {
            builder.region(Region.of(region));
        }
        ownClient = builder.build();
        return ownClient;
    }

    @Override
    public synchronized void destroy() {
        if (ownClient != null) {
            ownClient.close();
            ownClient = null;
        }
    }

    static class KnowledgeBasesConfigured extends SpringBootCondition {

        @Override
        public ConditionOutcome getMatchOutcome(ConditionContext context, AnnotatedTypeMetadata metadata) {
            boolean configured = Binder.get(context.getEnvironment())
                .bind("ai-insights.knowledge.bedrock.knowledge-base-ids", Bindable.listOf(String.class))
                .map(ids -> ids.stream().anyMatch(id -> !id.isBlank()))
                .orElse(false);
            return configured ? ConditionOutcome.match("Bedrock knowledge base IDs configured")
                    : ConditionOutcome.noMatch("no ai-insights.knowledge.bedrock.knowledge-base-ids");
        }
    }
}
