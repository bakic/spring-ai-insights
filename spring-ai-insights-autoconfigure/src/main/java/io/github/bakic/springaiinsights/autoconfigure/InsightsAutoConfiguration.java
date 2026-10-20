package io.github.bakic.springaiinsights.autoconfigure;

import java.nio.charset.StandardCharsets;
import java.util.List;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Tracer;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import io.github.bakic.springaiinsights.capture.CallerIdentityCapture;
import io.github.bakic.springaiinsights.capture.InsightsAdvisor;
import io.github.bakic.springaiinsights.capture.InsightsObservationHandler;
import io.github.bakic.springaiinsights.capture.TraceIdSupplier;
import io.github.bakic.springaiinsights.capture.TurnTimeouts;
import io.github.bakic.springaiinsights.identity.InsightsIdentityResolver;
import io.github.bakic.springaiinsights.identity.Pseudonymiser;
import io.github.bakic.springaiinsights.metrics.InsightsMetrics;
import io.github.bakic.springaiinsights.pricing.ConfiguredPriceCatalog;
import io.github.bakic.springaiinsights.pricing.CostCalculator;
import io.github.bakic.springaiinsights.pricing.ModelPrice;
import io.github.bakic.springaiinsights.pricing.PriceCatalog;
import io.github.bakic.springaiinsights.store.AsyncEventWriter;
import io.github.bakic.springaiinsights.store.InMemoryEventStore;
import io.github.bakic.springaiinsights.store.InsightsEventStore;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientBuilderCustomizer;
import org.springframework.ai.tool.execution.ToolExecutionExceptionProcessor;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.LazyInitializationExcludeFilter;
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

    static final String NO_SECRET_FAILURE = "ai-insights.identity.secret must be set when turns are stored "
            + "persistently: pseudonymised user IDs must stay stable across restarts";

    static final String MONGODB_MISSING = "ai-insights.store=mongodb requires Spring Data MongoDB and a single "
            + "MongoOperations bean (for example the auto-configured MongoTemplate)";

    static final String AWS_PRICING_MISSING = "ai-insights.pricing.aws.enabled=true requires "
            + "spring-ai-insights-bedrock and the AWS SDK modules software.amazon.awssdk:pricing and "
            + "software.amazon.awssdk:bedrock";

    static final String AWS_PRICING_CURRENCY = "ai-insights.pricing.aws.enabled=true requires "
            + "ai-insights.pricing.currency=USD (AWS list prices are in USD)";

    private static final Log logger = LogFactory.getLog(InsightsAutoConfiguration.class);

    public InsightsAutoConfiguration() {
        SpringAiVersionCheck.warnIfAffected(SpringAiVersionCheck.installedVersion());
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBooleanProperty(name = "ai-insights.identity.pseudonymise", matchIfMissing = true)
    Pseudonymiser insightsPseudonymiser(InsightsProperties properties, InsightsEventStore store) {
        String secret = properties.getIdentity().getSecret();
        if (!StringUtils.hasText(secret)) {
            if (!(store instanceof InMemoryEventStore)) {
                throw new IllegalStateException(NO_SECRET_FAILURE);
            }
            logger.warn(NO_SECRET_WARNING);
            return Pseudonymiser.withRandomSecret();
        }
        return new Pseudonymiser(secret.getBytes(StandardCharsets.UTF_8));
    }

    @Bean
    @ConditionalOnMissingBean
    PriceCatalog insightsPriceCatalog(InsightsProperties properties) {
        if (properties.getPricing().getAws().isEnabled()) {
            throw new IllegalStateException(AWS_PRICING_MISSING);
        }
        return configuredPriceCatalog(properties);
    }

    @Bean
    @ConditionalOnBooleanProperty(name = "ai-insights.pricing.aws.enabled")
    static SmartInitializingSingleton insightsAwsPriceCatalogCustomCatalogLog(
            ConfigurableListableBeanFactory beanFactory) {
        return () -> {
            if (!beanFactory.containsBean("insightsAwsPriceCatalog")) {
                logger.info("ai-insights: a custom PriceCatalog bean is defined, so ai-insights.pricing.aws is not "
                        + "applied");
            }
        };
    }

    static ConfiguredPriceCatalog configuredPriceCatalog(InsightsProperties properties) {
        InsightsProperties.Pricing pricing = properties.getPricing();
        List<ModelPrice> prices = pricing.getModels().stream().map(InsightsAutoConfiguration::modelPrice).toList();
        return new ConfiguredPriceCatalog(pricing.getCurrency(), prices);
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
    @ConditionalOnMissingBean(InsightsEventStore.class)
    InMemoryEventStore insightsEventStore(InsightsProperties properties) {
        if (properties.getStore() == InsightsProperties.StoreType.MONGODB) {
            throw new IllegalStateException(MONGODB_MISSING);
        }
        return new InMemoryEventStore(properties.getMemory().getMaxTurns());
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    AsyncEventWriter insightsEventWriter(InsightsEventStore store, InsightsProperties properties,
            InsightsMetrics metrics) {
        return new AsyncEventWriter(store, properties.getWriter().getQueueCapacity(), metrics);
    }

    @Bean
    @ConditionalOnClass(VectorStore.class)
    @ConditionalOnBooleanProperty(name = "ai-insights.knowledge.observe-stores", matchIfMissing = true)
    static ObservingVectorStorePostProcessor insightsObservingVectorStorePostProcessor(
            ObjectProvider<ObservationRegistry> observationRegistry) {
        return new ObservingVectorStorePostProcessor(observationRegistry);
    }

    @Bean
    @ConditionalOnClass(name = { "reactor.core.publisher.Hooks", "io.micrometer.context.ContextRegistry" })
    @ConditionalOnBooleanProperty(name = "ai-insights.capture.reactor-context-propagation", matchIfMissing = true)
    static ReactorContextPropagation insightsReactorContextPropagation() {
        return new ReactorContextPropagation();
    }

    @Bean
    @ConditionalOnClass(name = { "reactor.core.publisher.Hooks", "io.micrometer.context.ContextRegistry" })
    @ConditionalOnBooleanProperty(name = "ai-insights.capture.reactor-context-propagation", matchIfMissing = true)
    static LazyInitializationExcludeFilter insightsReactorContextPropagationEagerly() {
        return LazyInitializationExcludeFilter.forBeanTypes(ReactorContextPropagation.class);
    }

    @Bean
    @ConditionalOnClass(ToolExecutionExceptionProcessor.class)
    static ToolErrorRecordingPostProcessor insightsToolErrorRecordingPostProcessor(
            ObjectProvider<ObservationRegistry> observationRegistry) {
        return new ToolErrorRecordingPostProcessor(observationRegistry);
    }

    @Bean
    @ConditionalOnMissingBean
    InsightsObservationHandler insightsObservationHandler(InsightsMetrics metrics, InsightsProperties properties) {
        return new InsightsObservationHandler(metrics, properties.getKnowledge().getDocumentKey());
    }

    @Bean
    @ConditionalOnMissingBean
    CallerIdentityCapture insightsCallerIdentityCapture(InsightsIdentityResolver identityResolver,
            InsightsMetrics metrics) {
        return new CallerIdentityCapture(identityResolver, metrics);
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
            TraceIdSupplier traceIds, InsightsProperties properties, Environment environment) {
        return new InsightsAdvisor(observationRegistry.getIfUnique(() -> ObservationRegistry.NOOP), identityResolver,
                pseudonymiser.getIfAvailable(), costs, writer, metrics, application(properties, environment),
                properties.isCaptureContent(), timeouts, traceIds);
    }

    @Bean
    @ConditionalOnMissingBean
    TraceIdSupplier insightsTraceIdSupplier() {
        return TraceIdSupplier.NONE;
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
    @ConditionalOnClass(name = "io.micrometer.tracing.Tracer")
    static class TracingConfiguration {

        @Bean
        @ConditionalOnMissingBean
        TraceIdSupplier insightsTraceIdSupplier(ObjectProvider<Tracer> tracer) {
            Tracer unique = tracer.getIfUnique();
            return unique == null ? TraceIdSupplier.NONE : new MicrometerTraceIdSupplier(unique);
        }
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
