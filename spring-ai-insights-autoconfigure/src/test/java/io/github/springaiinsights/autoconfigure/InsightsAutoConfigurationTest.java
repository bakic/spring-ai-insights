package io.github.springaiinsights.autoconfigure;

import java.math.BigDecimal;
import java.util.List;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.github.springaiinsights.ScriptedChatModel;
import io.github.springaiinsights.capture.InsightsAdvisor;
import io.github.springaiinsights.capture.InsightsObservationHandler;
import io.github.springaiinsights.identity.InsightsIdentity;
import io.github.springaiinsights.identity.InsightsIdentityResolver;
import io.github.springaiinsights.identity.Pseudonymiser;
import io.github.springaiinsights.metrics.InsightsMetrics;
import io.github.springaiinsights.pricing.CostCalculator;
import io.github.springaiinsights.pricing.PriceCatalog;
import io.github.springaiinsights.store.AsyncEventWriter;
import io.github.springaiinsights.store.InMemoryEventStore;
import io.github.springaiinsights.store.InsightsEventStore;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientBuilderCustomizer;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.chat.client.autoconfigure.ChatClientAutoConfiguration;
import org.springframework.ai.model.tool.autoconfigure.ToolCallingAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.security.core.Authentication;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(OutputCaptureExtension.class)
class InsightsAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(InsightsAutoConfiguration.class))
        .withBean(SimpleMeterRegistry.class)
        .withBean(ObservationRegistry.class, ObservationRegistry::create);

    @Test
    void defaultsCreateAllBeans() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(Pseudonymiser.class)
                .hasSingleBean(PriceCatalog.class)
                .hasSingleBean(CostCalculator.class)
                .hasSingleBean(InsightsMetrics.class)
                .hasSingleBean(AsyncEventWriter.class)
                .hasSingleBean(InsightsIdentityResolver.class)
                .hasSingleBean(InsightsObservationHandler.class)
                .hasSingleBean(InsightsAdvisor.class)
                .hasSingleBean(ChatClientBuilderCustomizer.class);
            assertThat(context.getBean(InsightsEventStore.class)).isInstanceOf(InMemoryEventStore.class);
            assertThat(context.getBean(PriceCatalog.class).currency()).isEqualTo("USD");
        });
    }

    @Test
    void disabledCreatesNothing() {
        runner.withPropertyValues("ai-insights.enabled=false")
            .run(context -> assertThat(context).doesNotHaveBean(InsightsAdvisor.class)
                .doesNotHaveBean(InsightsProperties.class)
                .doesNotHaveBean(InsightsObservationHandler.class));
    }

    @Test
    void userBeansWin() {
        InsightsEventStore store = new InMemoryEventStore(5);
        InsightsIdentityResolver resolver = request -> new InsightsIdentity("u", "t");
        runner.withBean(InsightsEventStore.class, () -> store)
            .withBean(InsightsIdentityResolver.class, () -> resolver)
            .run(context -> {
                assertThat(context.getBean(InsightsEventStore.class)).isSameAs(store);
                assertThat(context.getBean(InsightsIdentityResolver.class)).isSameAs(resolver);
            });
    }

    @Test
    void pricesBindFromList() {
        runner.withPropertyValues("ai-insights.pricing.currency=EUR",
                "ai-insights.pricing.models[0].model=eu.anthropic.claude-x:0",
                "ai-insights.pricing.models[0].input=3", "ai-insights.pricing.models[0].output=15",
                "ai-insights.pricing.models[0].cache-read=0.3", "ai-insights.pricing.models[0].cache-write=3.75")
            .run(context -> {
                PriceCatalog catalog = context.getBean(PriceCatalog.class);
                assertThat(catalog.currency()).isEqualTo("EUR");
                var price = catalog.find("eu.anthropic.claude-x:0").orElseThrow();
                assertThat(price.input()).isEqualByComparingTo(new BigDecimal("3"));
                assertThat(price.output()).isEqualByComparingTo(new BigDecimal("15"));
                assertThat(price.cacheRead()).isEqualByComparingTo(new BigDecimal("0.3"));
                assertThat(price.cacheWrite()).isEqualByComparingTo(new BigDecimal("3.75"));
                assertThat(price.inputIncludesCached()).isFalse();
            });
    }

    @Test
    void priceWithoutOutputFailsStartup() {
        runner.withPropertyValues("ai-insights.pricing.models[0].model=m1", "ai-insights.pricing.models[0].input=3")
            .run(context -> assertThat(context).getFailure()
                .rootCause()
                .hasMessageContaining("needs model, input and output"));
    }

    @Test
    void noSecretWarnsAndUsesRandomKey(CapturedOutput output) {
        runner.run(context -> {
            assertThat(context).hasSingleBean(Pseudonymiser.class);
            assertThat(output).contains(InsightsAutoConfiguration.NO_SECRET_WARNING);
        });
        assertThat(InsightsAutoConfiguration.NO_SECRET_WARNING).isEqualTo(
                "ai-insights.identity.secret is not set: user IDs are pseudonymised with a random key and will change on restart");
    }

    @Test
    void secretGivesStablePseudonymsWithoutWarning(CapturedOutput output) {
        runner.withPropertyValues("ai-insights.identity.secret=k").run(context -> {
            assertThat(context.getBean(Pseudonymiser.class).pseudonymise("alice"))
                .isEqualTo(new Pseudonymiser("k".getBytes()).pseudonymise("alice"));
            assertThat(output).doesNotContain("ai-insights.identity.secret is not set");
        });
    }

    @Test
    void pseudonymiseOffCreatesNoPseudonymiser() {
        runner.withPropertyValues("ai-insights.identity.pseudonymise=false")
            .run(context -> assertThat(context).doesNotHaveBean(Pseudonymiser.class).hasSingleBean(InsightsAdvisor.class));
    }

    @Test
    void securityResolverChosenWhenSecurityPresent() {
        runner.run(context -> assertThat(context.getBean(InsightsIdentityResolver.class))
            .isInstanceOf(SpringSecurityIdentityResolver.class));
    }

    @Test
    void contextResolverWhenSecurityHidden() {
        runner.withClassLoader(new FilteredClassLoader(Authentication.class))
            .run(context -> assertThat(context.getBean(InsightsIdentityResolver.class))
                .isInstanceOf(ContextIdentityResolver.class));
    }

    @Test
    void autoConfiguredBuilderGetsTheAdvisor() {
        runner.withConfiguration(AutoConfigurations.of(ToolCallingAutoConfiguration.class, ChatClientAutoConfiguration.class))
            .withBean(ChatModel.class, () -> new ScriptedChatModel(ObservationRegistry.NOOP,
                    prompt -> List.of(ScriptedChatModel.text("hi", 1, 1))))
            .run(context -> {
                var response = context.getBean(ChatClient.Builder.class).build().prompt("hello").call().chatClientResponse();
                assertThat(response.context()).containsKey("ai-insights.turn-id");
            });
    }

    @Test
    void applicationDefaultsToSpringApplicationName() {
        runner.withPropertyValues("spring.application.name=shop")
            .run(context -> assertThat(InsightsAutoConfiguration.application(
                    context.getBean(InsightsProperties.class), context.getEnvironment())).isEqualTo("shop"));
        runner.run(context -> assertThat(InsightsAutoConfiguration.application(
                context.getBean(InsightsProperties.class), context.getEnvironment())).isEqualTo("default"));
        runner.withPropertyValues("spring.application.name=shop", "ai-insights.application=bot")
            .run(context -> assertThat(InsightsAutoConfiguration.application(
                    context.getBean(InsightsProperties.class), context.getEnvironment())).isEqualTo("bot"));
    }
}
