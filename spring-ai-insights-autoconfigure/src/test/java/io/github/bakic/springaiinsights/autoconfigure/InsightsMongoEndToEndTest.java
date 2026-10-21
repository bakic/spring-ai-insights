package io.github.bakic.springaiinsights.autoconfigure;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mongodb.MongoDBContainer;

import io.github.bakic.springaiinsights.ScriptedChatModel;
import io.github.bakic.springaiinsights.analytics.InsightsAnalytics;
import io.github.bakic.springaiinsights.analytics.InsightsFilter;
import io.github.bakic.springaiinsights.model.ConversationTurn;
import io.github.bakic.springaiinsights.mongodb.MongoEventStore;
import io.github.bakic.springaiinsights.store.InsightsEventStore;
import io.github.bakic.springaiinsights.store.TurnQuery;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;

import static io.github.bakic.springaiinsights.ScriptedChatModel.text;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class InsightsMongoEndToEndTest {

    @Nested
    @Testcontainers
    @SpringBootTest(classes = App.class,
            properties = { "ai-insights.identity.secret=k", "spring.application.name=mongo-e2e" })
    class WithMongo {

        @Container
        @ServiceConnection
        static MongoDBContainer mongo = new MongoDBContainer("mongo:7.0");

        @Autowired
        ChatClient.Builder builder;

        @Autowired
        InsightsEventStore store;

        @Autowired
        InsightsAnalytics analytics;

        @Test
        void chatTurnLandsInMongo() {
            assertThat(store).isInstanceOf(MongoEventStore.class);
            Instant before = Instant.now().minusSeconds(1);

            String answer = builder.build().prompt().user("hello").call().content();

            assertThat(answer).isEqualTo("answer");
            ConversationTurn turn = await().atMost(Duration.ofSeconds(5)).until(() -> latest(before), t -> t != null);
            assertThat(turn.streaming()).isFalse();
            assertThat(turn.modelCalls()).singleElement().satisfies(c -> assertThat(c.inputTokens()).isEqualTo(10));
            assertThat(analytics.summary(new InsightsFilter("mongo-e2e", before, Instant.now().plusSeconds(1), null,
                    ZoneOffset.UTC)).turns()).isGreaterThanOrEqualTo(1);
        }

        @Test
        void streamTurnLandsInMongo() {
            Instant before = Instant.now().minusSeconds(1);

            builder.build().prompt().user("hello").stream().content().blockLast();

            ConversationTurn turn = await().atMost(Duration.ofSeconds(5))
                .until(() -> latestStreaming(before), t -> t != null);
            assertThat(turn.application()).isEqualTo("mongo-e2e");
        }

        private ConversationTurn latest(Instant from) {
            List<ConversationTurn> turns = turns(from);
            return turns.stream().filter(t -> !t.streaming()).findFirst().orElse(null);
        }

        private ConversationTurn latestStreaming(Instant from) {
            return turns(from).stream().filter(ConversationTurn::streaming).findFirst().orElse(null);
        }

        private List<ConversationTurn> turns(Instant from) {
            return store.findTurns(new TurnQuery("mongo-e2e", from, Instant.now().plusSeconds(1), null, 10));
        }
    }

    @Nested
    @SpringBootTest(classes = App.class, properties = { "ai-insights.identity.secret=k",
            "spring.mongodb.uri=mongodb://localhost:1/x?serverSelectionTimeoutMS=300" })
    class MongoUnreachable {

        @Autowired
        ChatClient.Builder builder;

        @Autowired
        InsightsEventStore store;

        @Autowired
        MeterRegistry meters;

        @Test
        void chatWorksWhenMongoIsUnreachable() {
            assertThat(store).isInstanceOf(MongoEventStore.class);
            long start = System.nanoTime();

            String answer = builder.build().prompt().user("hello").call().content();

            assertThat(answer).isEqualTo("answer");
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(2));
            await().atMost(Duration.ofSeconds(10))
                .until(() -> meters.find("ai.insights.store.errors").counters().stream()
                    .mapToDouble(c -> c.count())
                    .sum() >= 1);
        }
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class App {

        @Bean
        ChatModel chatModel(ObservationRegistry registry) {
            return new ScriptedChatModel(registry, p -> List.of(text("answer", 10, 2)));
        }
    }
}
