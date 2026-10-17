package io.github.springaiinsights.autoconfigure;

import java.time.Duration;
import java.util.function.Supplier;

import io.github.springaiinsights.bedrock.pricing.AwsPriceCatalog;
import io.github.springaiinsights.pricing.PriceCatalog;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.jspecify.annotations.Nullable;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.regions.providers.DefaultAwsRegionProviderChain;
import software.amazon.awssdk.services.bedrock.BedrockClient;
import software.amazon.awssdk.services.bedrock.BedrockClientBuilder;
import software.amazon.awssdk.services.pricing.PricingClient;
import software.amazon.awssdk.services.pricing.PricingClientBuilder;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.util.StringUtils;

/**
 * Prices Amazon Bedrock model calls from the AWS Price List API ({@code pricing:GetProducts},
 * {@code bedrock:ListFoundationModels}), refreshed periodically, with the YAML catalog as fallback. Opt-in with
 * {@code ai-insights.pricing.aws.enabled=true}; backs off when the application defines its own {@link PriceCatalog}
 * bean (see {@link InsightsAutoConfiguration}). Reuses the application's {@link PricingClient} / {@link BedrockClient}
 * bean if there is exactly one of each, else builds (and closes) its own: the pricing client in
 * {@code ai-insights.pricing.aws.endpoint-region}, the Bedrock client in {@code ai-insights.pricing.aws.region}, else
 * {@code spring.ai.bedrock.aws.region}, else the AWS SDK default region chain. Clients are created lazily at the
 * first refresh, so a missing region or credentials never fails startup; only a non-USD currency or a missing
 * dependency does, by design (see {@link InsightsAutoConfiguration#AWS_PRICING_CURRENCY} and
 * {@link InsightsAutoConfiguration#AWS_PRICING_MISSING}).
 */
@AutoConfiguration(before = InsightsAutoConfiguration.class)
@ConditionalOnClass(name = { "io.github.springaiinsights.bedrock.pricing.AwsPriceCatalog",
        "software.amazon.awssdk.services.pricing.PricingClient", "software.amazon.awssdk.services.bedrock.BedrockClient" })
@ConditionalOnBooleanProperty(name = "ai-insights.enabled", matchIfMissing = true)
@ConditionalOnBooleanProperty(name = "ai-insights.pricing.aws.enabled")
@EnableConfigurationProperties(InsightsProperties.class)
public class InsightsBedrockPricingAutoConfiguration implements DisposableBean {

    private @Nullable PricingClient ownPricingClient;

    private @Nullable BedrockClient ownBedrockClient;

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(PriceCatalog.class)
    AwsPriceCatalog insightsAwsPriceCatalog(ObjectProvider<PricingClient> pricingClients,
            ObjectProvider<BedrockClient> bedrockClients, InsightsProperties properties,
            ObjectProvider<MeterRegistry> meterRegistry, Environment environment) {
        InsightsProperties.Pricing pricing = properties.getPricing();
        if (!"USD".equalsIgnoreCase(pricing.getCurrency())) {
            throw new IllegalStateException(InsightsAutoConfiguration.AWS_PRICING_CURRENCY);
        }
        InsightsProperties.Pricing.Aws aws = pricing.getAws();
        Supplier<String> region = () -> resolveRegion(aws, environment);
        AwsPriceCatalog catalog = new AwsPriceCatalog(
                () -> pricingClients.getIfUnique(() -> ownPricingClient(aws.getEndpointRegion())),
                () -> bedrockClients.getIfUnique(() -> ownBedrockClient(region.get())), region, aws.getRefresh(),
                InsightsAutoConfiguration.configuredPriceCatalog(properties),
                meterRegistry.getIfAvailable(SimpleMeterRegistry::new));
        catalog.start();
        return catalog;
    }

    private synchronized PricingClient ownPricingClient(String endpointRegion) {
        PricingClientBuilder builder = PricingClient.builder();
        if (StringUtils.hasText(endpointRegion)) {
            builder.region(Region.of(endpointRegion));
        }
        ownPricingClient = builder.build();
        return ownPricingClient;
    }

    private synchronized BedrockClient ownBedrockClient(String region) {
        BedrockClientBuilder builder = BedrockClient.builder();
        if (StringUtils.hasText(region)) {
            builder.region(Region.of(region));
        }
        ownBedrockClient = builder.build();
        return ownBedrockClient;
    }

    private static String resolveRegion(InsightsProperties.Pricing.Aws aws, Environment environment) {
        if (StringUtils.hasText(aws.getRegion())) {
            return aws.getRegion();
        }
        String springAiRegion = environment.getProperty("spring.ai.bedrock.aws.region");
        if (StringUtils.hasText(springAiRegion)) {
            return springAiRegion;
        }
        return new DefaultAwsRegionProviderChain().getRegion().id();
    }

    @Override
    public synchronized void destroy() {
        if (ownPricingClient != null) {
            ownPricingClient.close();
            ownPricingClient = null;
        }
        if (ownBedrockClient != null) {
            ownBedrockClient.close();
            ownBedrockClient = null;
        }
    }
}
