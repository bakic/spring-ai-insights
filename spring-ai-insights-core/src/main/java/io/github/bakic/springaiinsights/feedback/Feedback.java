package io.github.bakic.springaiinsights.feedback;

import java.time.Instant;
import java.util.Objects;

import org.jspecify.annotations.Nullable;

/**
 * A rating of one turn. Application, conversation, user and tenant are copied from the turn, so feedback can be
 * filtered like turns. There is at most one feedback per turn.
 */
public record Feedback(String turnId, String application, String conversationId, @Nullable String userId,
        @Nullable String tenantId, Rating rating, @Nullable String comment, Instant createdAt) {

    public Feedback {
        Objects.requireNonNull(turnId, "turnId");
        Objects.requireNonNull(application, "application");
        Objects.requireNonNull(conversationId, "conversationId");
        Objects.requireNonNull(rating, "rating");
        Objects.requireNonNull(createdAt, "createdAt");
    }
}
