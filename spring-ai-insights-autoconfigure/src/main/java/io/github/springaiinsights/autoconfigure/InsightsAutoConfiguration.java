package io.github.springaiinsights.autoconfigure;

import java.nio.charset.StandardCharsets;
import java.util.List;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import io.github.springaiinsights.capture.InsightsAdvisor;
import io.github.springaiinsights.capture.InsightsObservationHandler;
import io.github.springaiinsights.capture.TurnTimeouts;
import io.github.springaiinsights.identity.InsightsIdentityResolver;
import io.github.springaiinsights.identity.Pseudonymiser;
import io.github.springaiinsights.metrics.InsightsMetrics;
import io.github.springaiinsights.pricing.CostCalculator;
import io.github.springaiinsights.pricing.ModelPrice;
import io.github.springaiinsights.pricing.PriceCatalog;
import io.github.springaiinsights.store.AsyncEventWriter;
import io.github.springaiinsights.store.InMemoryEventStore;
import io.github.springaiinsights.store.InsightsEventStore;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientBuilderCustomizer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingClass;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.util.StringUtils;

/** Records every ChatClient turn: who asked, what it cost, which tools and knowledge bases it used. */
@AutoConfiguration
@ConditionalOnClass(ChatClient.class)
@ConditionalOnBooleanProperty(name = "ai-insights.enabled", matchIfMissing = true)
@EnableConfigurationProperties(InsightsProperties.class)
public class InsightsAutoConfiguration {

    static final String NO_SECRET_WARNING = "ai-insights.identity.secret is not set: user IDs are pseudonymised "
            + "with a random key and will change on restart";

    private static final Log logger = LogFactory.getLog(InsightsAutoConfiguration.class);

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBooleanProperty(name = "ai-insights.identity.pseudonymise", matchIfMissing = true)
    Pseudonymiser insightsPseudonymiser(InsightsProperties properties) {
        String secret = properties.getIdentity().getSecret();
        if (!StringUtils.hasText(secret)) {
            logger.warn(NO_SECRET_WARNING);
            return Pseudonymiser.withRandomSecret();
        }
        return new Pseudonymiser(secret.getBytes(StandardCharsets.UTF_8));
    }

    @Bean
    @ConditionalOnMissingBean
    PriceCatalog insightsPriceCatalog(InsightsProperties properties) {
        InsightsProperties.Pricing pricing = properties.getPricing();
        List<ModelPrice> prices = pricing.getModels().stream().map(InsightsAutoConfiguration::modelPrice).toList();
        return new PriceCatalog(pricing.getCurrency(), prices);
    }

    @Bean
    @ConditionalOnMissingBean
    InsightsMetrics insightsMetrics(ObjectProvider<MeterRegistry> meterRegistry, InsightsProperties properties,
            Environment environment) {
        return new InsightsMetrics(meterRegistry.getIfAvailable(SimpleMeterRegistry::new),
                application(properties, environment), properties.getMetrics().isTenantTag());
    }

    @Bean
    @ConditionalOnMissingBean
    CostCalculator insightsCostCalculator(PriceCatalog catalog, InsightsMetrics metrics) {
        return new CostCalculator(catalog, metrics);
    }

    @Bean
    @ConditionalOnMissingBean
    InsightsEventStore insightsEventStore(InsightsProperties properties) {
        return new InMemoryEventStore(properties.getMemory().getMaxTurns());
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    AsyncEventWriter insightsEventWriter(InsightsEventStore store, InsightsProperties properties,
            InsightsMetrics metrics) {
        return new AsyncEventWriter(store, properties.getWriter().getQueueCapacity(), metrics);
    }

    @Bean
    @ConditionalOnMissingBean
    InsightsObservationHandler insightsObservationHandler(InsightsMetrics metrics) {
        return new InsightsObservationHandler(metrics);
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    TurnTimeouts insightsTurnTimeouts(InsightsProperties properties) {
        return new TurnTimeouts(properties.getCapture().getStallTimeout());
    }

    @Bean
    @ConditionalOnMissingBean
    InsightsAdvisor insightsAdvisor(ObjectProvider<ObservationRegistry> observationRegistry,
            InsightsIdentityResolver identityResolver, ObjectProvider<Pseudonymiser> pseudonymiser,
            CostCalculator costs, AsyncEventWriter writer, InsightsMetrics metrics, TurnTimeouts timeouts,
            InsightsProperties properties, Environment environment) {
        return new InsightsAdvisor(observationRegistry.getIfUnique(() -> ObservationRegistry.NOOP), identityResolver,
                pseudonymiser.getIfAvailable(), costs, writer, metrics, application(properties, environment),
                properties.isCaptureContent(), timeouts);
    }

    @Bean
    @ConditionalOnMissingBean(name = "insightsChatClientBuilderCustomizer")
    ChatClientBuilderCustomizer insightsChatClientBuilderCustomizer(InsightsAdvisor advisor) {
        return builder -> builder.defaultAdvisors(advisor);
    }

    static String application(InsightsProperties properties, Environment environment) {
        if (StringUtils.hasText(properties.getApplication())) {
            return properties.getApplication();
        }
        return environment.getProperty("spring.application.name", "default");
    }

    private static ModelPrice modelPrice(InsightsProperties.ModelPricing price) {
        if (!StringUtils.hasText(price.getModel()) || price.getInput() == null || price.getOutput() == null) {
            throw new IllegalStateException(
                    "Each ai-insights.pricing.models entry needs model, input and output; got model="
                            + price.getModel() + ", input=" + price.getInput() + ", output=" + price.getOutput());
        }
        return new ModelPrice(price.getModel(), price.getInput(), price.getOutput(), price.getCacheRead(),
                price.getCacheWrite(), price.isInputIncludesCached());
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.springframework.security.core.Authentication")
    static class SecurityIdentityConfiguration {

        @Bean
        @ConditionalOnMissingBean
        InsightsIdentityResolver insightsIdentityResolver(InsightsProperties properties) {
            return new SpringSecurityIdentityResolver(properties.getIdentity().getTenantClaim());
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnMissingClass("org.springframework.security.core.Authentication")
    static class ContextIdentityConfiguration {

        @Bean
        @ConditionalOnMissingBean
        InsightsIdentityResolver insightsIdentityResolver() {
            return new ContextIdentityResolver();
        }
    }
}
