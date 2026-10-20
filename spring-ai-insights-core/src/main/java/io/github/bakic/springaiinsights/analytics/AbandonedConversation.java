package io.github.bakic.springaiinsights.analytics;

import java.time.Instant;

import org.jspecify.annotations.Nullable;

/** A conversation whose last turn failed, was cancelled or was rated down, and that was not resumed. */
public record AbandonedConversation(String conversationId, @Nullable String userId, String lastTurnId,
        Instant lastTurnAt, AbandonReason reason) {
}
