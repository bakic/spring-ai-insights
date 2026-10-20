package io.github.bakic.springaiinsights.autoconfigure;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;

import io.github.bakic.springaiinsights.feedback.InsightsFeedback;
import io.github.bakic.springaiinsights.knowledge.KnowledgeCorpusCatalog;
import io.github.bakic.springaiinsights.model.ConversationTurn;
import io.github.bakic.springaiinsights.store.InsightsEventStore;
import io.github.bakic.springaiinsights.store.TurnQuery;
import io.github.bakic.springaiinsights.web.FeedbackRoutes;

import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

class InsightsWebAutoConfigurationTest {

    private static final AutoConfigurations CONFIGS = AutoConfigurations.of(InsightsAutoConfiguration.class,
            InsightsFeedbackAutoConfiguration.class, InsightsWebAutoConfiguration.class);

    private final WebApplicationContextRunner web = new WebApplicationContextRunner().withConfiguration(CONFIGS)
        .withBean(SimpleMeterRegistry.class)
        .withBean(ObservationRegistry.class, ObservationRegistry::create);

    @Test
    void feedbackWithBundledStore() {
        web.run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(InsightsFeedback.class);
            assertThat(context).hasBean("insightsFeedbackRoutes");
        });
    }

    @Test
    void noFeedbackForCustomStoreWithoutFeedbackStore() {
        web.withBean(InsightsEventStore.class, CustomStore::new).withPropertyValues("ai-insights.identity.secret=k").run(context -> {
            assertThat(context).hasNotFailed().doesNotHaveBean(InsightsFeedback.class);
            assertThat(context).doesNotHaveBean("insightsFeedbackRoutes");
        });
    }

    @Test
    void feedbackRoutesOffWhenDisabled() {
        web.withPropertyValues("ai-insights.feedback.enabled=false").run(context -> {
            assertThat(context).hasSingleBean(InsightsFeedback.class);
            assertThat(context).doesNotHaveBean("insightsFeedbackRoutes");
        });
    }

    @Test
    void noRoutesOutsideServletApps() {
        new ApplicationContextRunner().withConfiguration(CONFIGS)
            .withBean(SimpleMeterRegistry.class)
            .run(context -> {
                assertThat(context).hasNotFailed().hasSingleBean(InsightsFeedback.class);
                assertThat(context).doesNotHaveBean("insightsFeedbackRoutes");
            });
    }

    @Test
    void noRoutesWithoutWebModule() {
        web.withClassLoader(new FilteredClassLoader(FeedbackRoutes.class)).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean("insightsFeedbackRoutes");
        });
    }

    @Test
    void invalidDashboardPathFails() {
        for (String path : List.of("ai-insights", "/", "/a/", "/a b", "//a")) {
            web.withPropertyValues("ai-insights.dashboard.path=" + path)
                .run(context -> assertThat(context).as(path)
                    .hasFailed()
                    .getFailure()
                    .rootCause()
                    .hasMessageContaining("ai-insights.dashboard.path must look like /ai-insights"));
        }
    }

    @Test
    void customDashboardPath() {
        web.withPropertyValues("ai-insights.dashboard.path=/ops/ai").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(InsightsProperties.class).getDashboard().getPath()).isEqualTo("/ops/ai");
        });
    }

    @Test
    void dashboardWithSpringSecurity() {
        web.run(context -> assertThat(context).hasBean("insightsDashboardRoutes"));
    }

    @Test
    void noSecurityNoDashboard() {
        web.withClassLoader(new FilteredClassLoader(SecurityFilterChain.class)).run(context -> {
            assertThat(context).hasNotFailed().doesNotHaveBean("insightsDashboardRoutes");
            assertThat(context).hasBean("insightsFeedbackRoutes");
        });
        web.withClassLoader(new FilteredClassLoader(SecurityFilterChain.class))
            .withPropertyValues("ai-insights.dashboard.allow-anonymous=true")
            .run(context -> assertThat(context).hasBean("insightsDashboardRoutes"));
    }

    @Test
    void dashboardOffWhenDisabled() {
        web.withPropertyValues("ai-insights.dashboard.enabled=false").run(context -> {
            assertThat(context).doesNotHaveBean("insightsDashboardRoutes");
            assertThat(context).hasBean("insightsFeedbackRoutes");
        });
    }

    @Test
    void noDashboardForCustomStoreWithoutAnalytics() {
        web.withBean(InsightsEventStore.class, CustomStore::new)
            .withPropertyValues("ai-insights.identity.secret=k")
            .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean("insightsDashboardRoutes"));
    }

    @Test
    void traceUrlMustBeHttpTemplate() {
        for (String url : List.of("javascript:alert(1)/{traceId}", "https://x/", "ftp://x/{traceId}")) {
            web.withPropertyValues("ai-insights.dashboard.trace-url=" + url)
                .run(context -> assertThat(context).as(url)
                    .hasFailed()
                    .getFailure()
                    .rootCause()
                    .hasMessageContaining("ai-insights.dashboard.trace-url"));
        }
    }

    @Test
    void abandonedAfterBelowOneMinuteIsRejected() {
        web.withPropertyValues("ai-insights.quality.abandoned-after=30s")
            .run(context -> assertThat(context).hasFailed()
                .getFailure()
                .rootCause()
                .hasMessageContaining("abandoned-after must be at least 1 minute"));
    }

    @Test
    void catalogsAndTraceUrlReachDashboard() {
        KnowledgeCorpusCatalog catalog = new KnowledgeCorpusCatalog() {
            @Override
            public String store() {
                return "kb";
            }

            @Override
            public Set<String> documentIds() {
                return Set.of("d1");
            }
        };
        web.withBean(KnowledgeCorpusCatalog.class, () -> catalog)
            .withPropertyValues("ai-insights.dashboard.allow-anonymous=true",
                    "ai-insights.dashboard.trace-url=https://tempo.example/trace/{traceId}")
            .run(context -> {
                @SuppressWarnings("unchecked")
                RouterFunction<ServerResponse> routes = context.getBean("insightsDashboardRoutes",
                        RouterFunction.class);
                MockMvc mvc = MockMvcBuilders.routerFunctions(routes).build();
                mvc.perform(get("/ai-insights/api/config"))
                    .andExpect(jsonPath("$.traceUrl").value("https://tempo.example/trace/{traceId}"));
                mvc.perform(get("/ai-insights/api/knowledge"))
                    .andExpect(jsonPath("$.coverage[0].store").value("kb"))
                    .andExpect(jsonPath("$.coverage[0].documents").value(1));
            });
    }

    @Test
    void noDashboardOutsideServletApps() {
        new ApplicationContextRunner().withConfiguration(CONFIGS)
            .withBean(SimpleMeterRegistry.class)
            .run(context -> assertThat(context).doesNotHaveBean("insightsDashboardRoutes"));
    }

    static class CustomStore implements InsightsEventStore {

        @Override
        public void save(ConversationTurn turn) {
        }

        @Override
        public Optional<ConversationTurn> findTurn(String turnId) {
            return Optional.empty();
        }

        @Override
        public List<ConversationTurn> findTurns(TurnQuery query) {
            return List.of();
        }
    }
}
