package io.github.springaiinsights.store;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.springaiinsights.TestTurns;
import io.github.springaiinsights.metrics.InsightsMetrics;
import io.github.springaiinsights.model.ConversationTurn;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AsyncEventWriterTest {

    private final SimpleMeterRegistry reg = new SimpleMeterRegistry();

    private final InsightsMetrics metrics = new InsightsMetrics(reg, "app", false);

    private static ConversationTurn turn(String id) {
        return TestTurns.minimal().withTurnId(id).build();
    }

    @Test
    void writerSavesSubmittedTurns() {
        var store = new InMemoryEventStore(100);
        try (var w = new AsyncEventWriter(store, 10, metrics)) {
            assertThat(w.submit(turn("t1"))).isTrue();
        }
        assertThat(store.findTurn("t1")).isPresent();
    }

    @Test
    void writerSurvivesStoreFailure() {
        var calls = new AtomicInteger();
        var saved = new InMemoryEventStore(100);
        InsightsEventStore failing = new DelegatingStore(saved) {
            @Override
            public void save(ConversationTurn t) {
                if (calls.getAndIncrement() == 0) {
                    throw new IllegalStateException("db down");
                }
                super.save(t);
            }
        };
        try (var w = new AsyncEventWriter(failing, 10, metrics)) {
            w.submit(turn("t1"));
            w.submit(turn("t2"));
        }
        assertThat(saved.findTurn("t1")).isEmpty();
        assertThat(saved.findTurn("t2")).isPresent();
        assertThat(reg.get("ai.insights.store.errors").counter().count()).isEqualTo(1);
    }

    @Test
    void fullQueueDropsAndCounts() throws Exception {
        var release = new CountDownLatch(1);
        var entered = new CountDownLatch(1);
        InsightsEventStore blocking = new DelegatingStore(new InMemoryEventStore(10)) {
            @Override
            public void save(ConversationTurn t) {
                entered.countDown();
                try {
                    release.await(5, TimeUnit.SECONDS);
                }
                catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        };
        try (var w = new AsyncEventWriter(blocking, 1, metrics)) {
            List<Boolean> results = new ArrayList<>();
            results.add(w.submit(turn("t1")));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            results.add(w.submit(turn("t2")));
            results.add(w.submit(turn("t3")));
            assertThat(results).contains(false);
            assertThat(reg.get("ai.insights.events.dropped").counter().count()).isGreaterThanOrEqualTo(1);
            release.countDown();
        }
    }

    private static class DelegatingStore implements InsightsEventStore {

        private final InsightsEventStore delegate;

        DelegatingStore(InsightsEventStore delegate) {
            this.delegate = delegate;
        }

        @Override
        public void save(ConversationTurn turn) {
            delegate.save(turn);
        }

        @Override
        public Optional<ConversationTurn> findTurn(String turnId) {
            return delegate.findTurn(turnId);
        }

        @Override
        public List<ConversationTurn> findTurns(TurnQuery query) {
            return delegate.findTurns(query);
        }
    }
}
