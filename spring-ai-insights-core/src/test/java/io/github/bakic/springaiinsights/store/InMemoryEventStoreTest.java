package io.github.bakic.springaiinsights.store;

import java.time.Instant;

import io.github.bakic.springaiinsights.TestTurns;
import io.github.bakic.springaiinsights.feedback.Feedback;
import io.github.bakic.springaiinsights.feedback.Rating;
import io.github.bakic.springaiinsights.model.ConversationTurn;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryEventStoreTest {

    private static final Instant T0 = Instant.parse("2026-10-01T10:00:00Z");

    private static ConversationTurn turn(String id, String app, String tenant, int minutes) {
        return TestTurns.minimal().withTurnId(id).withApplication(app).withTenantId(tenant)
            .withStartedAt(T0.plusSeconds(60L * minutes)).build();
    }

    @Test
    void evictsOldestWhenFull() {
        var store = new InMemoryEventStore(2);
        store.save(turn("t1", "a", null, 0));
        store.save(turn("t2", "a", null, 1));
        store.save(turn("t3", "a", null, 2));
        assertThat(store.findTurn("t1")).isEmpty();
        assertThat(store.findTurn("t2")).isPresent();
        assertThat(store.findTurn("t3")).isPresent();
    }

    @Test
    void feedbackIsBoundedOldestFirstOut() {
        var store = new InMemoryEventStore(2);
        for (String id : new String[] { "t1", "t2", "t1", "t3" }) {
            store.saveFeedback(new Feedback(id, "a", "c", null, null, Rating.UP, null, T0));
        }
        assertThat(store.conversationFeedback("a", "c")).extracting(Feedback::turnId)
            .containsExactlyInAnyOrder("t2", "t3");
    }

    @Test
    void savingSameIdTwiceKeepsOneEntry() {
        var store = new InMemoryEventStore(2);
        store.save(turn("t1", "a", null, 0));
        store.save(turn("t1", "a", null, 0));
        store.save(turn("t2", "a", null, 1));
        assertThat(store.findTurn("t1")).isPresent();
        assertThat(store.findTurn("t2")).isPresent();
    }

    @Test
    void queryFiltersByAppTenantAndWindowNewestFirst() {
        var store = new InMemoryEventStore(100);
        store.save(turn("a1", "a", "x", 0));
        store.save(turn("a2", "a", "y", 1));
        store.save(turn("a3", "a", "x", 2));
        store.save(turn("b1", "b", "x", 1));
        store.save(turn("a4", "a", "x", 10));

        var window = new TurnQuery("a", T0, T0.plusSeconds(600), null, 10);
        assertThat(store.findTurns(window)).extracting(ConversationTurn::turnId).containsExactly("a3", "a2", "a1");

        var tenant = new TurnQuery("a", T0, T0.plusSeconds(600), "x", 10);
        assertThat(store.findTurns(tenant)).extracting(ConversationTurn::turnId).containsExactly("a3", "a1");

        var limited = new TurnQuery("a", T0, T0.plusSeconds(3600), null, 2);
        assertThat(store.findTurns(limited)).extracting(ConversationTurn::turnId).containsExactly("a4", "a3");
    }
}
