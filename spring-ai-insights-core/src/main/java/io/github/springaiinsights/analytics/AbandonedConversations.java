package io.github.springaiinsights.analytics;

import java.util.List;

/** How many conversations were abandoned, and the most recent of them. */
public record AbandonedConversations(long count, List<AbandonedConversation> recent) {

    public AbandonedConversations {
        recent = List.copyOf(recent);
    }
}
