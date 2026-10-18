package io.github.springaiinsights.autoconfigure;

import java.lang.reflect.Method;

import io.github.springaiinsights.bedrock.BedrockKnowledgeBaseCatalog;
import io.github.springaiinsights.knowledge.KnowledgeCorpusCatalog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.bedrockagent.BedrockAgentClient;
import software.amazon.awssdk.services.bedrockagent.model.ListDataSourcesRequest;
import software.amazon.awssdk.services.bedrockagent.model.ListDataSourcesResponse;

import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class InsightsBedrockAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(InsightsBedrockAutoConfiguration.class));

    /** Any call from a client built by the auto-configuration is refused locally instead of reaching AWS. */
    @BeforeEach
    void noRealAws() {
        System.setProperty("aws.endpointUrl", "http://127.0.0.1:9");
    }

    @AfterEach
    void restore() {
        System.clearProperty("aws.endpointUrl");
    }

    private static BedrockAgentClient emptyClient() {
        return new BedrockAgentClient() {
            @Override
            public ListDataSourcesResponse listDataSources(ListDataSourcesRequest request) {
                return ListDataSourcesResponse.builder().build();
            }

            @Override
            public String serviceName() {
                return "bedrock";
            }

            @Override
            public void close() {
            }
        };
    }

    @Test
    void catalogWhenKnowledgeBaseConfigured() {
        runner.withBean(BedrockAgentClient.class, InsightsBedrockAutoConfigurationTest::emptyClient)
            .withPropertyValues("ai-insights.knowledge.bedrock.knowledge-base-ids=KB1")
            .run(context -> {
                assertThat(context).hasSingleBean(KnowledgeCorpusCatalog.class);
                assertThat(context.getBean(KnowledgeCorpusCatalog.class).store()).isEqualTo("bedrock_knowledge_base");
            });
    }

    @Test
    void ownClientWhenNoneDefined() {
        runner.withPropertyValues("ai-insights.knowledge.bedrock.knowledge-base-ids=KB1",
                "ai-insights.knowledge.bedrock.region=eu-west-1")
            .run(context -> {
                assertThat(context).hasSingleBean(BedrockKnowledgeBaseCatalog.class);
                assertThat(context).doesNotHaveBean(BedrockAgentClient.class);
            });
    }

    /** The catalog calls its client supplier on every refresh; the own client must be built once, not leaked. */
    @Test
    void ownClientIsReusedAcrossCalls() throws Exception {
        InsightsBedrockAutoConfiguration autoConfiguration = new InsightsBedrockAutoConfiguration();
        try {
            Method ownClient = InsightsBedrockAutoConfiguration.class.getDeclaredMethod("ownClient", String.class);
            ownClient.setAccessible(true);
            assertThat(ownClient.invoke(autoConfiguration, "eu-west-1"))
                .isSameAs(ownClient.invoke(autoConfiguration, "eu-west-1"));
        }
        finally {
            autoConfiguration.destroy();
        }
    }

    @Test
    void missingRegionDoesNotFailStartup() {
        String region = System.getProperty("aws.region");
        System.setProperty("aws.region", "");
        try {
            runner.withPropertyValues("ai-insights.knowledge.bedrock.knowledge-base-ids=KB1").run(context -> {
                assertThat(context).hasNotFailed();
                BedrockKnowledgeBaseCatalog catalog = context.getBean(BedrockKnowledgeBaseCatalog.class);
                catalog.refresh();
                assertThat(catalog.documentIds()).isNull();
            });
        }
        finally {
            if (region == null) {
                System.clearProperty("aws.region");
            }
            else {
                System.setProperty("aws.region", region);
            }
        }
    }

    @Test
    void noCatalogWithoutProperty() {
        runner.withBean(BedrockAgentClient.class, InsightsBedrockAutoConfigurationTest::emptyClient)
            .run(context -> assertThat(context).doesNotHaveBean(KnowledgeCorpusCatalog.class));
    }

    @Test
    void noCatalogWithoutSdk() {
        runner.withClassLoader(new FilteredClassLoader(BedrockAgentClient.class))
            .withPropertyValues("ai-insights.knowledge.bedrock.knowledge-base-ids=KB1")
            .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(KnowledgeCorpusCatalog.class));
    }

    @Test
    void refreshBelowOneMinuteIsRejected() {
        runner.withBean(BedrockAgentClient.class, InsightsBedrockAutoConfigurationTest::emptyClient)
            .withPropertyValues("ai-insights.knowledge.bedrock.knowledge-base-ids=KB1",
                    "ai-insights.knowledge.bedrock.refresh=30s")
            .run(context -> assertThat(context).getFailure()
                .rootCause()
                .hasMessageContaining("refresh must be at least 1 minute"));
    }
}
