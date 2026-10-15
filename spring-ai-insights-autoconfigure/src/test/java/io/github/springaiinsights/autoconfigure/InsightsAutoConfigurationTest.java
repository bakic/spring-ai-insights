package io.github.springaiinsights.autoconfigure;

import java.math.BigDecimal;
import java.util.List;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.test.simple.SimpleTracer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.github.springaiinsights.ScriptedChatModel;
import io.github.springaiinsights.capture.InsightsAdvisor;
import io.github.springaiinsights.capture.InsightsObservationHandler;
import io.github.springaiinsights.capture.TraceIdSupplier;
import io.github.springaiinsights.knowledge.ObservedVectorStore;
import io.github.springaiinsights.capture.TurnTimeouts;
import io.github.springaiinsights.analytics.InsightsAnalytics;
import io.github.springaiinsights.mongodb.MongoEventStore;
import com.mongodb.client.MongoClients;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.MongoTemplate;
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
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.model.chat.client.autoconfigure.ChatClientAutoConfiguration;
import org.springframework.ai.model.tool.autoconfigure.ToolCallingAutoConfiguration;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
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
    void documentKeyDefaultsToSource() {
        runner.run(context -> assertThat(context.getBean(InsightsObservationHandler.class).documentKey())
            .isEqualTo("source"));
    }

    @Test
    void documentKeyIsConfigurable() {
        runner.withPropertyValues("ai-insights.knowledge.document-key=file_name")
            .run(context -> assertThat(context.getBean(InsightsObservationHandler.class).documentKey())
                .isEqualTo("file_name"));
        runner.withPropertyValues("ai-insights.knowledge.document-key=")
            .run(context -> assertThat(context.getBean(InsightsObservationHandler.class).documentKey()).isNull());
    }

    @Test
    void plainStoreIsWrapped() {
        runner.withBean("kb", VectorStore.class, PlainStore::new).run(context -> {
            assertThat(context.getBean("kb")).isInstanceOf(ObservedVectorStore.class);
            assertThat(((ObservedVectorStore) context.getBean("kb")).delegate()).isInstanceOf(PlainStore.class);
        });
    }

    @Test
    void alreadyObservedStoreIsNotWrapped() {
        SimpleVectorStore simple = SimpleVectorStore.builder(new NoEmbedding()).build();
        runner.withBean("simple", SimpleVectorStore.class, () -> simple)
            .run(context -> assertThat(context.getBean("simple")).isSameAs(simple));
    }

    @Test
    void reactorContextPropagationIsOnByDefault() {
        runner.run(context -> assertThat(context).hasSingleBean(ReactorContextPropagation.class));
    }

    @Test
    void reactorContextPropagationIsTurnedOnEvenWithLazyInitialization() {
        runner.withInitializer(context -> context
            .addBeanFactoryPostProcessor(new org.springframework.boot.LazyInitializationBeanFactoryPostProcessor()))
            .run(context -> assertThat(context.getBeanFactory().containsSingleton("insightsReactorContextPropagation"))
                .isTrue());
    }

    @Test
    void reactorContextPropagationCanBeLeftOff() {
        runner.withPropertyValues("ai-insights.capture.reactor-context-propagation=false")
            .run(context -> assertThat(context).doesNotHaveBean(ReactorContextPropagation.class));
    }

    @Test
    void wrappingCanBeDisabled() {
        runner.withPropertyValues("ai-insights.knowledge.observe-stores=false")
            .withBean("kb", VectorStore.class, PlainStore::new)
            .run(context -> assertThat(context.getBean("kb")).isInstanceOf(PlainStore.class));
    }

    @Test
    void storeInjectedByItsClassKeepsItsTypeAndIsObserved() {
        List<String> observed = new java.util.concurrent.CopyOnWriteArrayList<>();
        ObservationRegistry registry = ObservationRegistry.create();
        registry.observationConfig().observationHandler(new io.micrometer.observation.ObservationHandler<>() {
            @Override
            public boolean supportsContext(io.micrometer.observation.Observation.Context context) {
                return context instanceof org.springframework.ai.vectorstore.observation.VectorStoreObservationContext;
            }

            @Override
            public void onStop(io.micrometer.observation.Observation.Context context) {
                observed.add(((org.springframework.ai.vectorstore.observation.VectorStoreObservationContext) context)
                    .getQueryRequest()
                    .getQuery());
            }
        });
        new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(InsightsAutoConfiguration.class))
            .withBean(SimpleMeterRegistry.class)
            .withBean(ObservationRegistry.class, () -> registry)
            .withBean("kb", OpenStore.class, OpenStore::new)
            .withBean(StoreUser.class)
            .run(context -> {
                assertThat(context).hasNotFailed();
                OpenStore store = context.getBean(StoreUser.class).store;
                assertThat(store.extra()).isEqualTo("extra");
                store.similaritySearch(SearchRequest.builder().query("by request").build());
                store.similaritySearch("by text");
                assertThat(observed).containsExactly("by request", "by text");
            });
    }

    static class OpenStore implements VectorStore {

        String extra() {
            return "extra";
        }

        @Override
        public void add(List<Document> documents) {
        }

        @Override
        public void delete(List<String> idList) {
        }

        @Override
        public void delete(Filter.Expression filterExpression) {
        }

        @Override
        public List<Document> similaritySearch(SearchRequest request) {
            return List.of();
        }
    }

    static class StoreUser {

        final OpenStore store;

        StoreUser(OpenStore store) {
            this.store = store;
        }
    }

    static final class PlainStore implements VectorStore {

        @Override
        public void add(List<Document> documents) {
        }

        @Override
        public void delete(List<String> idList) {
        }

        @Override
        public void delete(Filter.Expression filterExpression) {
        }

        @Override
        public List<Document> similaritySearch(SearchRequest request) {
            return List.of();
        }
    }

    static final class NoEmbedding implements EmbeddingModel {

        @Override
        public EmbeddingResponse call(EmbeddingRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public float[] embed(Document document) {
            throw new UnsupportedOperationException();
        }
    }

    @Test
    void stallTimeoutBinds() {
        runner.withPropertyValues("ai-insights.capture.stall-timeout=5s").run(context -> {
            assertThat(context).hasSingleBean(TurnTimeouts.class);
            assertThat(context.getBean(InsightsProperties.class).getCapture().getStallTimeout())
                .isEqualTo(java.time.Duration.ofSeconds(5));
        });
    }

    @Test
    void nonPositiveStallTimeoutFailsStartup() {
        runner.withPropertyValues("ai-insights.capture.stall-timeout=0s")
            .run(context -> assertThat(context).hasFailed()
                .getFailure()
                .rootCause()
                .hasMessageContaining("stallTimeout must be positive"));
    }

    private static final String NO_SECRET_FAILURE = "ai-insights.identity.secret must be set when turns are stored "
            + "persistently: pseudonymised user IDs must stay stable across restarts";

    private ApplicationContextRunner withMongo() {
        return runner.withConfiguration(AutoConfigurations.of(InsightsMongoAutoConfiguration.class))
            .withBean("mongoTemplate", MongoTemplate.class,
                    () -> new MongoTemplate(MongoClients.create("mongodb://localhost:1/?serverSelectionTimeoutMS=100"),
                            "db"));
    }

    @Test
    void mongoStoreChosenWhenMongoOperationsPresent() {
        withMongo().withPropertyValues("ai-insights.identity.secret=k").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(InsightsEventStore.class)).isInstanceOf(MongoEventStore.class);
            assertThat(context.getBean(InsightsAnalytics.class)).isSameAs(context.getBean(InsightsEventStore.class));
        });
    }

    @Test
    void memoryStoreWhenForced() {
        withMongo().withPropertyValues("ai-insights.store=memory")
            .run(context -> assertThat(context.getBean(InsightsEventStore.class)).isInstanceOf(InMemoryEventStore.class));
    }

    @Test
    void mongodbForcedWithoutMongoFails() {
        runner.withPropertyValues("ai-insights.store=mongodb", "ai-insights.identity.secret=k")
            .run(context -> assertThat(context).hasFailed()
                .getFailure()
                .rootCause()
                .hasMessage("ai-insights.store=mongodb requires Spring Data MongoDB and a single MongoOperations "
                        + "bean (for example the auto-configured MongoTemplate)"));
    }

    @Test
    void memoryWhenSpringDataMongoAbsent() {
        withMongo().withClassLoader(new FilteredClassLoader(MongoOperations.class))
            .run(context -> assertThat(context.getBean(InsightsEventStore.class)).isInstanceOf(InMemoryEventStore.class));
    }

    @Test
    void persistentStoreWithoutSecretFails() {
        withMongo().run(context -> assertThat(context).hasFailed()
            .getFailure()
            .rootCause()
            .hasMessage(NO_SECRET_FAILURE));
    }

    @Test
    void customStoreWithoutSecretFails() {
        InsightsEventStore custom = new InsightsEventStore() {
            @Override
            public void save(io.github.springaiinsights.model.ConversationTurn turn) {
            }

            @Override
            public java.util.Optional<io.github.springaiinsights.model.ConversationTurn> findTurn(String turnId) {
                return java.util.Optional.empty();
            }

            @Override
            public java.util.List<io.github.springaiinsights.model.ConversationTurn> findTurns(
                    io.github.springaiinsights.store.TurnQuery query) {
                return java.util.List.of();
            }
        };
        runner.withBean(InsightsEventStore.class, () -> custom)
            .run(context -> assertThat(context).hasFailed().getFailure().rootCause().hasMessage(NO_SECRET_FAILURE));
    }

    @Test
    void persistentStoreWithoutPseudonymisationNeedsNoSecret() {
        withMongo().withPropertyValues("ai-insights.identity.pseudonymise=false")
            .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void retentionAndPrefixBind() {
        withMongo().withPropertyValues("ai-insights.identity.secret=k", "ai-insights.retention=30d",
                "ai-insights.mongodb.collection-prefix=x_")
            .run(context -> {
                MongoEventStore store = context.getBean(MongoEventStore.class);
                assertThat(store.collectionName()).isEqualTo("x_turns");
                assertThat(store.retention()).isEqualTo(java.time.Duration.ofDays(30));
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
    void tracerGivesMicrometerSupplier() {
        runner.withBean(Tracer.class, SimpleTracer::new)
            .run(context -> assertThat(context.getBean(TraceIdSupplier.class))
                .isInstanceOf(MicrometerTraceIdSupplier.class));
    }

    @Test
    void noTracerGivesNone() {
        runner.run(context -> assertThat(context.getBean(TraceIdSupplier.class)).isSameAs(TraceIdSupplier.NONE));
    }

    @Test
    void tracingAbsentFromClasspathGivesNone() {
        runner.withClassLoader(new FilteredClassLoader(Tracer.class))
            .run(context -> assertThat(context.getBean(TraceIdSupplier.class)).isSameAs(TraceIdSupplier.NONE));
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
