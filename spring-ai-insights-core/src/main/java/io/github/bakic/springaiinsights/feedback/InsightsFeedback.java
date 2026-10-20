package io.github.bakic.springaiinsights.feedback;

import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Optional;

import io.github.bakic.springaiinsights.identity.Pseudonymiser;
import io.github.bakic.springaiinsights.metrics.InsightsMetrics;
import io.github.bakic.springaiinsights.model.ConversationTurn;
import io.github.bakic.springaiinsights.store.AsyncEventWriter;
import io.github.bakic.springaiinsights.store.InsightsEventStore;
import org.jspecify.annotations.Nullable;

/**
 * Records a user's rating of one of their own turns. A turn that does not exist and a turn that belongs to someone
 * else give the same result, so callers cannot probe for other users' turn IDs.
 */
public final class InsightsFeedback {

    public static final int MAX_COMMENT_LENGTH = 1000;

    private final InsightsEventStore turns;

    private final FeedbackStore store;

    private final AsyncEventWriter writer;

    private final @Nullable Pseudonymiser pseudonymiser;

    private final InsightsMetrics metrics;

    private final Clock clock;

    public InsightsFeedback(InsightsEventStore turns, FeedbackStore store, AsyncEventWriter writer,
            @Nullable Pseudonymiser pseudonymiser, InsightsMetrics metrics, Clock clock) {
        this.turns = turns;
        this.store = store;
        this.writer = writer;
        this.pseudonymiser = pseudonymiser;
        this.metrics = metrics;
        this.clock = clock;
    }

    /**
     * Rates a turn on behalf of a user.
     * @param userId the raw user ID, as the identity resolver saw it, or {@code null} for an anonymous user
     * @return {@code false} when the turn is unknown or was not made by this user
     * @throws IllegalArgumentException when the comment is longer than {@value #MAX_COMMENT_LENGTH} characters
     */
    public boolean submit(String turnId, @Nullable String userId, Rating rating, @Nullable String comment) {
        Objects.requireNonNull(turnId, "turnId");
        Objects.requireNonNull(rating, "rating");
        String text = comment == null || comment.isBlank() ? null : comment.strip();
        if (text != null && text.length() > MAX_COMMENT_LENGTH) {
            throw new IllegalArgumentException("comment must not exceed " + MAX_COMMENT_LENGTH + " characters");
        }
        // Writer first: a turn leaves the writer only once it is in the store, so this order has no gap.
        Optional<ConversationTurn> found = writer.pending(turnId).or(() -> turns.findTurn(turnId));
        if (found.isEmpty()) {
            return false;
        }
        ConversationTurn turn = found.get();
        String expectedUser = userId == null || pseudonymiser == null ? userId : pseudonymiser.pseudonymise(userId);
        if (!Objects.equals(turn.userId(), expectedUser)) {
            return false;
        }
        store.saveFeedback(new Feedback(turn.turnId(), turn.application(), turn.conversationId(), turn.userId(),
                turn.tenantId(), rating, text, clock.instant().truncatedTo(ChronoUnit.MILLIS)));
        metrics.feedback(rating);
        return true;
    }
}
