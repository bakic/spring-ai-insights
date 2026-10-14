package io.github.springaiinsights.autoconfigure;

import io.github.springaiinsights.metrics.InsightsMetrics;
import io.github.springaiinsights.feedback.InsightsFeedback;
import io.github.springaiinsights.web.FeedbackRoutes;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerResponse;

/** Serves the feedback endpoint and the dashboard in servlet applications. */
@AutoConfiguration(after = InsightsFeedbackAutoConfiguration.class)
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
}
