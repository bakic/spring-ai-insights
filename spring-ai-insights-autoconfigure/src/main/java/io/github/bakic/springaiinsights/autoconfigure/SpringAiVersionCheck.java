package io.github.bakic.springaiinsights.autoconfigure;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.model.tool.ToolCallingManager;

/**
 * Warns about Spring AI versions on which the starter cannot attribute everything to a turn.
 *
 * <p>Spring AI 2.0.0's {@code DefaultToolCallingManager} gives each tool observation no parent when the
 * call is not reactive, so tool calls, and knowledge base searches made inside a tool, are not linked to
 * the turn that triggered them. Spring AI 2.0.1 fixed it.
 */
final class SpringAiVersionCheck {

    private static final Log logger = LogFactory.getLog(SpringAiVersionCheck.class);

    private SpringAiVersionCheck() {
    }

    static void warnIfAffected(@Nullable String springAiVersion) {
        String problem = problem(springAiVersion);
        if (problem != null) {
            logger.warn(problem);
        }
    }

    static @Nullable String problem(@Nullable String springAiVersion) {
        if (!"2.0.0".equals(springAiVersion)) {
            return null;
        }
        return "Spring AI 2.0.0 is on the classpath: its tool calls are not linked to the turn that made them, "
                + "so tool calls and the knowledge base searches made inside tools are missing from AI insights. "
                + "Upgrade Spring AI to 2.0.1 or later.";
    }

    /** The Spring AI version from the spring-ai-model jar manifest, or null when it is not available. */
    static @Nullable String installedVersion() {
        return ToolCallingManager.class.getPackage().getImplementationVersion();
    }

}
