package io.github.bakic.springaiinsights.autoconfigure;

import org.springframework.boot.autoconfigure.condition.ConditionOutcome;
import org.springframework.boot.autoconfigure.condition.SpringBootCondition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.util.ClassUtils;

/**
 * The dashboard needs Spring Security to check its role; without it, it is served only when anonymous access is
 * explicitly allowed.
 */
class DashboardAccessCondition extends SpringBootCondition {

    static final String SECURITY_FILTER_CHAIN = "org.springframework.security.web.SecurityFilterChain";

    @Override
    public ConditionOutcome getMatchOutcome(ConditionContext context, AnnotatedTypeMetadata metadata) {
        if (ClassUtils.isPresent(SECURITY_FILTER_CHAIN, context.getClassLoader())) {
            return ConditionOutcome.match("Spring Security is present");
        }
        if (context.getEnvironment().getProperty("ai-insights.dashboard.allow-anonymous", Boolean.class, false)) {
            return ConditionOutcome.match("ai-insights.dashboard.allow-anonymous is true");
        }
        return ConditionOutcome.noMatch("Spring Security is absent and ai-insights.dashboard.allow-anonymous is false");
    }
}
