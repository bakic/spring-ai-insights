package io.github.springaiinsights.feedback;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import io.github.springaiinsights.TestTurns;
import io.github.springaiinsights.identity.Pseudonymiser;
import io.github.springaiinsights.metrics.InsightsMetrics;
import io.github.springaiinsights.model.ConversationTurn;
import io.github.springaiinsights.store.AsyncEventWriter;
import io.github.springaiinsights.store.InMemoryEventStore;
import io.github.springaiinsights.store.InsightsEventStore;
import io.github.springaiinsights.store.TurnQuery;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class InsightsFeedbackTest {

    private static final Instant NOW = Instant.parse("2026-10-01T10:00:00Z");

    private final Pseudonymiser pseudonymiser = new Pseudonymiser("k".getBytes(UTF_8));

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    private final InsightsMetrics metrics = new InsightsMetrics(registry, "shop", false);

    private final InMemoryEventStore store = new InMemoryEventStore(100);

    private final AsyncEventWriter writer = new AsyncEventWriter(store, 10, metrics);

    private final InsightsFeedback feedback = new InsightsFeedback(store, store, writer, pseudonymiser, metrics,
            Clock.fixed(NOW, ZoneOffset.UTC));

    @AfterEach
    void close() {
        writer.close();
    }

    private static ConversationTurn turn(String id, String userId) {
        return TestTurns.minimal().withTurnId(id).withApplication("shop").withConversationId("c1")
            .withUserId(userId).withTenantId("acme").build();
    }

    @Test
    void ownTurnIsRated() {
        store.save(turn("t1", pseudonymiser.pseudonymise("alice")));

        assertThat(feedback.submit("t1", "alice", Rating.UP, " great ")).isTrue();

        assertThat(store.conversationFeedback("shop", "c1")).containsExactly(new Feedback("t1", "shop", "c1",
                pseudonymiser.pseudonymise("alice"), "acme", Rating.UP, "great", NOW));
        assertThat(registry.get("ai.insights.feedback").tags("application", "shop", "rating", "up").counter().count())
            .isEqualTo(1);
    }

    @Test
    void othersTurnIsNotFound() {
        store.save(turn("t1", pseudonymiser.pseudonymise("alice")));

        assertThat(feedback.submit("t1", "bob", Rating.UP, null)).isFalse();

        assertThat(store.conversationFeedback("shop", "c1")).isEmpty();
        assertThat(registry.find("ai.insights.feedback").counter()).isNull();
    }

    @Test
    void unknownTurnIsNotFound() {
        assertThat(feedback.submit("nope", "alice", Rating.UP, null)).isFalse();
    }

    @Test
    void anonymousTurnAcceptsAnonymousCaller() {
        store.save(turn("t1", null));

        assertThat(feedback.submit("t1", "alice", Rating.DOWN, null)).isFalse();
        assertThat(feedback.submit("t1", null, Rating.DOWN, null)).isTrue();
    }

    @Test
    void namedTurnRejectsAnonymousCaller() {
        store.save(turn("t1", pseudonymiser.pseudonymise("alice")));

        assertThat(feedback.submit("t1", null, Rating.UP, null)).isFalse();
    }

    @Test
    void newRatingReplacesOld() {
        store.save(turn("t1", pseudonymiser.pseudonymise("alice")));

        feedback.submit("t1", "alice", Rating.UP, null);
        feedback.submit("t1", "alice", Rating.DOWN, "wrong");

        assertThat(store.conversationFeedback("shop", "c1")).singleElement().satisfies(f -> {
            assertThat(f.rating()).isEqualTo(Rating.DOWN);
            assertThat(f.comment()).isEqualTo("wrong");
        });
    }

    @Test
    void commentLimits() {
        store.save(turn("t1", pseudonymiser.pseudonymise("alice")));

        feedback.submit("t1", "alice", Rating.UP, "   ");
        assertThat(store.conversationFeedback("shop", "c1").getFirst().comment()).isNull();

        assertThat(feedback.submit("t1", "alice", Rating.UP, "x".repeat(1000))).isTrue();
        assertThatIllegalArgumentException()
            .isThrownBy(() -> feedback.submit("t1", "alice", Rating.UP, "x".repeat(1001)))
            .withMessage("comment must not exceed 1000 characters");
    }

    @Test
    void rawIdsWhenPseudonymiserNull() {
        var raw = new InsightsFeedback(store, store, writer, null, metrics, Clock.fixed(NOW, ZoneOffset.UTC));
        store.save(turn("t1", "alice"));

        assertThat(raw.submit("t1", "alice", Rating.UP, null)).isTrue();
    }

    @Test
    void turnStillInWriterQueueAcceptsFeedback() throws InterruptedException {
        var saving = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        InsightsEventStore slow = new InsightsEventStore() {
            @Override
            public void save(ConversationTurn turn) {
                saving.countDown();
                try {
                    release.await(5, TimeUnit.SECONDS);
                }
                catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }

            @Override
            public Optional<ConversationTurn> findTurn(String turnId) {
                return Optional.empty();
            }

            @Override
            public List<ConversationTurn> findTurns(TurnQuery query) {
                return List.of();
            }
        };
        try (var slowWriter = new AsyncEventWriter(slow, 10, metrics)) {
            var service = new InsightsFeedback(slow, store, slowWriter, pseudonymiser, metrics,
                    Clock.fixed(NOW, ZoneOffset.UTC));
            slowWriter.submit(turn("t1", pseudonymiser.pseudonymise("alice")));
            assertThat(saving.await(5, TimeUnit.SECONDS)).isTrue();

            assertThat(service.submit("t1", "alice", Rating.UP, null)).isTrue();
            release.countDown();
        }
        assertThat(store.conversationFeedback("shop", "c1")).hasSize(1);
    }

    @Test
    void turnSavedWhileLookingItUpAcceptsFeedback() throws InterruptedException {
        var saving = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var saved = new java.util.concurrent.atomic.AtomicReference<ConversationTurn>();
        var writerRef = new java.util.concurrent.atomic.AtomicReference<AsyncEventWriter>();
        InsightsEventStore racing = new InsightsEventStore() {
            @Override
            public void save(ConversationTurn turn) {
                saving.countDown();
                try {
                    release.await(5, TimeUnit.SECONDS);
                }
                catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                saved.set(turn);
            }

            // Reads the store before the save commits, then returns only after the writer has finished it.
            @Override
            public Optional<ConversationTurn> findTurn(String turnId) {
                Optional<ConversationTurn> snapshot = Optional.ofNullable(saved.get());
                release.countDown();
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (writerRef.get().pending(turnId).isPresent() && System.nanoTime() < deadline) {
                    Thread.onSpinWait();
                }
                return snapshot;
            }

            @Override
            public List<ConversationTurn> findTurns(TurnQuery query) {
                return List.of();
            }
        };
        try (var racingWriter = new AsyncEventWriter(racing, 10, metrics)) {
            writerRef.set(racingWriter);
            var service = new InsightsFeedback(racing, store, racingWriter, pseudonymiser, metrics,
                    Clock.fixed(NOW, ZoneOffset.UTC));
            racingWriter.submit(turn("t1", pseudonymiser.pseudonymise("alice")));
            assertThat(saving.await(5, TimeUnit.SECONDS)).isTrue();

            assertThat(service.submit("t1", "alice", Rating.UP, null)).isTrue();
            release.countDown();
        }
        assertThat(store.conversationFeedback("shop", "c1")).hasSize(1);
    }
}
