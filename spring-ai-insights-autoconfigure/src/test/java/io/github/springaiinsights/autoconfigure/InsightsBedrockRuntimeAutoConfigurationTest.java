package io.github.springaiinsights.autoconfigure;

import io.github.springaiinsights.bedrock.ObservedBedrockAgentRuntime;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.bedrockagentruntime.BedrockAgentRuntimeClient;

import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class InsightsBedrockRuntimeAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(InsightsBedrockRuntimeAutoConfiguration.class))
        .withBean(ObservationRegistry.class, ObservationRegistry::create)
        .withBean("runtime", BedrockAgentRuntimeClient.class, PlainRuntime::new);

    @Test
    void runtimeClientIsObservedWithoutAnyKnowledgeBaseConfigured() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            BedrockAgentRuntimeClient client = context.getBean(BedrockAgentRuntimeClient.class);
            assertThat(ObservedBedrockAgentRuntime.isObserved(client)).isTrue();
            assertThat(client.serviceName()).isEqualTo("plain");
        });
    }

    @Test
    void observationCanBeTurnedOff() {
        runner.withPropertyValues("ai-insights.knowledge.observe-stores=false")
            .run(context -> assertThat(context.getBean("runtime")).isInstanceOf(PlainRuntime.class));
    }

    @Test
    void insightsDisabledLeavesTheClientAlone() {
        runner.withPropertyValues("ai-insights.enabled=false")
            .run(context -> assertThat(context.getBean("runtime")).isInstanceOf(PlainRuntime.class));
    }

    @Test
    void nothingHappensWithoutTheRuntimeSdk() {
        new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(InsightsBedrockRuntimeAutoConfiguration.class))
            .withClassLoader(new FilteredClassLoader(BedrockAgentRuntimeClient.class))
            .run(context -> assertThat(context).hasNotFailed()
                .doesNotHaveBean("insightsObservingBedrockAgentRuntimePostProcessor"));
    }

    static final class PlainRuntime implements BedrockAgentRuntimeClient {

        @Override
        public String serviceName() {
            return "plain";
        }

        @Override
        public void close() {
        }
    }
}
