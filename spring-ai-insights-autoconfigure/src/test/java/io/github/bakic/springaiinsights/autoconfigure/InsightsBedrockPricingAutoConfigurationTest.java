package io.github.bakic.springaiinsights.autoconfigure;

import java.lang.reflect.Method;
import java.math.BigDecimal;

import io.github.bakic.springaiinsights.bedrock.pricing.AwsPriceCatalog;
import io.github.bakic.springaiinsights.pricing.ConfiguredPriceCatalog;
import io.github.bakic.springaiinsights.pricing.ModelPrice;
import io.github.bakic.springaiinsights.pricing.PriceCatalog;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.bedrock.BedrockClient;
import software.amazon.awssdk.services.bedrock.model.ListFoundationModelsRequest;
import software.amazon.awssdk.services.bedrock.model.ListFoundationModelsResponse;
import software.amazon.awssdk.services.pricing.PricingClient;
import software.amazon.awssdk.services.pricing.model.GetProductsRequest;
import software.amazon.awssdk.services.pricing.model.GetProductsResponse;

import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(OutputCaptureExtension.class)
class InsightsBedrockPricingAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner().withConfiguration(
            AutoConfigurations.of(InsightsBedrockPricingAutoConfiguration.class, InsightsAutoConfiguration.class));

    /** Always throws: the catalog must never actually reach AWS in tests. */
    private static PricingClient throwingPricingClient() {
        return new PricingClient() {

            @Override
            public GetProductsResponse getProducts(GetProductsRequest request) {
                throw new UnsupportedOperationException("tests must never call AWS");
            }

            @Override
            public String serviceName() {
                return "pricing";
            }

            @Override
            public void close() {
            }
        };
    }

    private static BedrockClient throwingBedrockClient() {
        return new BedrockClient() {

            @Override
            public ListFoundationModelsResponse listFoundationModels(ListFoundationModelsRequest request) {
                throw new UnsupportedOperationException("tests must never call AWS");
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

    private ApplicationContextRunner withFakeClients() {
        return runner.withBean(PricingClient.class, InsightsBedrockPricingAutoConfigurationTest::throwingPricingClient)
            .withBean(BedrockClient.class, InsightsBedrockPricingAutoConfigurationTest::throwingBedrockClient);
    }

    @Test
    void disabledByDefault() {
        withFakeClients().run(context -> assertThat(context).getBean(PriceCatalog.class)
            .isInstanceOf(ConfiguredPriceCatalog.class));
    }

    @Test
    void enabledRegistersAwsCatalog() {
        withFakeClients()
            .withPropertyValues("ai-insights.pricing.aws.enabled=true", "ai-insights.pricing.aws.region=eu-west-1")
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).getBean(PriceCatalog.class).isInstanceOf(AwsPriceCatalog.class);
            });
    }

    @Test
    void nonUsdCurrencyFailsStartup() {
        withFakeClients()
            .withPropertyValues("ai-insights.pricing.aws.enabled=true", "ai-insights.pricing.currency=EUR")
            .run(context -> assertThat(context).getFailure()
                .rootCause()
                .hasMessageContaining(InsightsAutoConfiguration.AWS_PRICING_CURRENCY));
    }

    @Test
    void missingSdkFailsStartup() {
        withFakeClients().withClassLoader(new FilteredClassLoader(PricingClient.class))
            .withPropertyValues("ai-insights.pricing.aws.enabled=true")
            .run(context -> assertThat(context).getFailure()
                .rootCause()
                .hasMessageContaining(InsightsAutoConfiguration.AWS_PRICING_MISSING));
    }

    @Test
    void customCatalogWins(CapturedOutput output) {
        ConfiguredPriceCatalog custom = new ConfiguredPriceCatalog("USD", java.util.List.of());
        withFakeClients().withBean(PriceCatalog.class, () -> custom)
            .withPropertyValues("ai-insights.pricing.aws.enabled=true")
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context.getBean(PriceCatalog.class)).isSameAs(custom);
                assertThat(context).doesNotHaveBean("insightsAwsPriceCatalog");
            });
        assertThat(output).contains(
                "ai-insights: a custom PriceCatalog bean is defined, so ai-insights.pricing.aws is not applied");
    }

    @Test
    void awsCatalogFallsBackToYaml() {
        withFakeClients()
            .withPropertyValues("ai-insights.pricing.aws.enabled=true", "ai-insights.pricing.models[0].model=claude-sonnet-4",
                    "ai-insights.pricing.models[0].input=3.00", "ai-insights.pricing.models[0].output=15.00")
            .run(context -> {
                assertThat(context).hasNotFailed();
                PriceCatalog catalog = context.getBean(PriceCatalog.class);
                ModelPrice price = catalog.find("eu.anthropic.claude-sonnet-4-6").orElseThrow();
                assertThat(price.input()).isEqualByComparingTo(BigDecimal.valueOf(3.00));
            });
    }

    /** The suppliers passed to AwsPriceCatalog are called on every refresh; the own client built on the first
     * call must be reused (and only closed once, in destroy()), not rebuilt and leaked on every refresh. */
    @Test
    void ownClientsAreReusedAcrossCalls() throws Exception {
        InsightsBedrockPricingAutoConfiguration autoConfiguration = new InsightsBedrockPricingAutoConfiguration();
        try {
            Method ownPricingClient = InsightsBedrockPricingAutoConfiguration.class
                .getDeclaredMethod("ownPricingClient", String.class);
            ownPricingClient.setAccessible(true);
            PricingClient firstPricing = (PricingClient) ownPricingClient.invoke(autoConfiguration, "us-east-1");
            PricingClient secondPricing = (PricingClient) ownPricingClient.invoke(autoConfiguration, "us-east-1");
            assertThat(firstPricing).isSameAs(secondPricing);

            Method ownBedrockClient = InsightsBedrockPricingAutoConfiguration.class
                .getDeclaredMethod("ownBedrockClient", String.class);
            ownBedrockClient.setAccessible(true);
            BedrockClient firstBedrock = (BedrockClient) ownBedrockClient.invoke(autoConfiguration, "eu-west-1");
            BedrockClient secondBedrock = (BedrockClient) ownBedrockClient.invoke(autoConfiguration, "eu-west-1");
            assertThat(firstBedrock).isSameAs(secondBedrock);
        }
        finally {
            autoConfiguration.destroy();
        }
    }

    @Test
    void refreshBelowOneHourFailsStartup() {
        withFakeClients()
            .withPropertyValues("ai-insights.pricing.aws.enabled=true", "ai-insights.pricing.aws.refresh=30m")
            .run(context -> assertThat(context).getFailure()
                .rootCause()
                .hasMessageContaining("refresh must be at least 1 hour"));
    }
}
