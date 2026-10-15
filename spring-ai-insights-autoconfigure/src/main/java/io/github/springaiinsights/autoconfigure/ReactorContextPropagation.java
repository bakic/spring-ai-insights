package io.github.springaiinsights.autoconfigure;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import reactor.core.publisher.Hooks;

/**
 * Turns on Reactor's automatic context propagation, so the current observation follows a streamed turn onto the
 * threads Reactor switches to. Without it, work that Spring AI advisors do on another thread, such as the search
 * {@code QuestionAnswerAdvisor} runs on {@code boundedElastic}, has no parent observation and is not part of its
 * turn. The switch is JVM-wide; Spring Boot sets the same one for {@code spring.reactor.context-propagation=auto}.
 */
final class ReactorContextPropagation {

    private static final Log logger = LogFactory.getLog(ReactorContextPropagation.class);

    ReactorContextPropagation() {
        Hooks.enableAutomaticContextPropagation();
        logger.info("Spring AI Insights enabled Reactor automatic context propagation, so searches and tool calls of "
                + "streamed turns are recorded. Set ai-insights.capture.reactor-context-propagation=false to leave "
                + "it off.");
    }
}
