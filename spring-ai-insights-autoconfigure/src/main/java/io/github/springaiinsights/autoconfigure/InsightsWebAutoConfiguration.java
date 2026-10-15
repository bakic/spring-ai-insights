package io.github.springaiinsights.autoconfigure;

import java.time.Clock;

import io.github.springaiinsights.analytics.InsightsAnalytics;
import io.github.springaiinsights.feedback.InsightsFeedback;
import io.github.springaiinsights.knowledge.KnowledgeCorpusCatalog;
import io.github.springaiinsights.metrics.InsightsMetrics;
import io.github.springaiinsights.pricing.PriceCatalog;
import io.github.springaiinsights.report.WeeklyReports;
import io.github.springaiinsights.store.InsightsEventStore;
import io.github.springaiinsights.web.DashboardOptions;
import io.github.springaiinsights.web.DashboardPages;
import io.github.springaiinsights.web.DashboardRoutes;
import io.github.springaiinsights.web.FeedbackRoutes;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.core.env.Environment;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerResponse;

/** Serves the feedback endpoint and the dashboard in servlet applications. */
@AutoConfiguration(after = { InsightsFeedbackAutoConfiguration.class, InsightsReportAutoConfiguration.class })
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(name = { "org.springframework.web.servlet.function.RouterFunction",
        "io.github.springaiinsights.web.FeedbackRoutes" })
public class InsightsWebAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(name = "insightsFeedbackRoutes")
    @ConditionalOnBean({ InsightsFeedback.class, InsightsMetrics.class })
    @ConditionalOnBooleanProperty(name = "ai-insights.feedback.enabled", matchIfMissing = true)
    RouterFunction<ServerResponse> insightsFeedbackRoutes(InsightsProperties properties, InsightsFeedback feedback,
            InsightsMetrics metrics) {
        return FeedbackRoutes.create(properties.getDashboard().getPath(), feedback, metrics);
    }

    @Bean
    @ConditionalOnMissingBean(name = "insightsDashboardRoutes")
    @ConditionalOnBean({ InsightsAnalytics.class, InsightsEventStore.class, PriceCatalog.class })
    @ConditionalOnBooleanProperty(name = "ai-insights.dashboard.enabled", matchIfMissing = true)
    @Conditional(DashboardAccessCondition.class)
    RouterFunction<ServerResponse> insightsDashboardRoutes(InsightsProperties properties,
            InsightsAnalytics analytics, InsightsEventStore store, PriceCatalog prices, Environment environment,
            ObjectProvider<KnowledgeCorpusCatalog> catalogs, ObjectProvider<WeeklyReports> reports) {
        InsightsProperties.Dashboard dashboard = properties.getDashboard();
        Clock clock = Clock.systemUTC();
        DashboardPages pages = new DashboardPages(analytics, store,
                InsightsAutoConfiguration.application(properties, environment), prices.currency(), clock,
                new DashboardOptions(catalogs.orderedStream().toList(),
                        properties.getQuality().getAbandonedAfter(), dashboard.getTraceUrl()));
        return DashboardRoutes.create(dashboard.getPath(), dashboard.getRole(), dashboard.isAllowAnonymous(), pages,
                clock, reports.getIfAvailable());
    }
}
