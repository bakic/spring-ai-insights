package io.github.springaiinsights.identity;

/** Keys read from / written to the ChatClient advisor context. */
public final class InsightsContextKeys {

    /** Set on every ChatClientResponse context: the ID of the recorded turn. */
    public static final String TURN_ID = "ai-insights.turn-id";

    /** Optional advisor param overriding the resolved user ID. */
    public static final String USER_ID = "ai-insights.user-id";

    /** Optional advisor param overriding the resolved tenant ID. */
    public static final String TENANT_ID = "ai-insights.tenant-id";

    /** Advisor params with this prefix become turn attributes (prefix stripped). */
    public static final String ATTRIBUTE_PREFIX = "ai-insights.attr.";

    private InsightsContextKeys() {
    }
}
