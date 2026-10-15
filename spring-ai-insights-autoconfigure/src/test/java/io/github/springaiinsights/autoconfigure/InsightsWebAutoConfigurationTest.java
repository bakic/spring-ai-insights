package io.github.springaiinsights.autoconfigure;

import java.util.List;
import java.util.Optional;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;

import io.github.springaiinsights.feedback.InsightsFeedback;
import io.github.springaiinsights.model.ConversationTurn;
import io.github.springaiinsights.store.InsightsEventStore;
import io.github.springaiinsights.store.TurnQuery;
import io.github.springaiinsights.web.FeedbackRoutes;

import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.security.web.SecurityFilterChain;

import static org.assertj.core.api.Assertions.assertThat;

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
