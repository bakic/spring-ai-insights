package io.github.bakic.springaiinsights.mongodb;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import com.mongodb.client.MongoClients;
import io.github.bakic.springaiinsights.analytics.ContractTurns;
import io.github.bakic.springaiinsights.feedback.Feedback;
import io.github.bakic.springaiinsights.feedback.Rating;
import io.github.bakic.springaiinsights.model.ConversationTurn;
import io.github.bakic.springaiinsights.model.ModelCall;
import io.github.bakic.springaiinsights.model.RetrievalRecord;
import io.github.bakic.springaiinsights.model.ToolCallRecord;
import io.github.bakic.springaiinsights.model.ToolOutcome;
import io.github.bakic.springaiinsights.model.TurnOutcome;
import io.github.bakic.springaiinsights.store.TurnQuery;
import org.bson.Document;
import org.bson.types.Decimal128;
import org.junit.jupiter.api.Test;

import org.springframework.data.mongodb.core.MongoTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MongoEventStoreTest {

    private static final Duration NINETY_DAYS = Duration.ofDays(90);

    private final MongoTemplate template = MongoTestSupport.freshTemplate();

    private final MongoEventStore store = new MongoEventStore(template, "ai_insights_", NINETY_DAYS);

    private static ConversationTurn full() {
        return new ConversationTurn("full-1", "shop", "c1", true, Instant.parse("2026-10-01T08:00:00.123Z"), "u1",
                "acme", true, TurnOutcome.ERROR, "IllegalStateException", 1234, 56L, List.of("STOP", "LENGTH"),
                List.of(new ModelCall("bedrock", "m1", 1, 2, 3, 4, 5, new BigDecimal("0.0000012345"), "Timeout")),
                List.of(new ToolCallRecord("lookup", 7, ToolOutcome.ERROR, "IOException", 2)),
                List.of(new RetrievalRecord("kb", 4, 2, List.of(0.75, 0.5), List.of("d1", "d2"), 9)),
                new BigDecimal("0.0000012345"), "EUR", Map.of("a.b", "1", "$x", "2"), "question", "answer",
                List.of("lookup", "refund"), "4bf92f3577b34da6a3ce929d0e0e4736");
    }

    private static ConversationTurn nulls() {
        return new ConversationTurn("nulls-1", "shop", "c2", false, Instant.parse("2026-10-01T09:00:00Z"), null, null,
                false, TurnOutcome.SUCCESS, null, 10, null, List.of(),
                List.of(new ModelCall("p", "m2", 0, 0, 0, 0, 0, null, null)),
                List.of(new ToolCallRecord("t", 1, ToolOutcome.SUCCESS, null, 0)),
                List.of(new RetrievalRecord("kb", null, 0, List.of(), List.of(), 0)), null, "USD", Map.of(), null,
                null, List.of(), null);
    }

    private Document raw(String id) {
        return template.getCollection("ai_insights_turns").find(new Document("_id", id)).first();
    }

    @Test
    void everyFieldRoundTrips() {
        store.save(full());
        assertThat(store.findTurn("full-1")).contains(full());
    }

    @Test
    void nullsRoundTrip() {
        store.save(nulls());
        assertThat(store.findTurn("nulls-1")).contains(nulls());
        assertThat(raw("nulls-1")).doesNotContainKeys("userMessage", "assistantMessage", "traceId");
    }

    @Test
    void readsTurnWithoutNewFields() {
        store.save(full());
        template.getCollection("ai_insights_turns")
            .updateOne(new Document("_id", "full-1"),
                    new Document("$unset", new Document("offeredTools", "").append("traceId", "")));
        assertThat(store.findTurn("full-1")).get().satisfies(turn -> {
            assertThat(turn.offeredTools()).isEmpty();
            assertThat(turn.traceId()).isNull();
        });
    }

    @Test
    void costIsStoredAsDecimal128() {
        store.save(full());
        Document doc = raw("full-1");
        assertThat(doc.get("totalCost")).isInstanceOf(Decimal128.class);
        assertThat(doc.getList("modelCalls", Document.class).get(0).get("cost")).isInstanceOf(Decimal128.class);
        assertThat(doc.get("startedAt")).isInstanceOf(java.util.Date.class);
        assertThat(doc.getInteger("schemaVersion")).isEqualTo(1);
    }

    @Test
    void missingTurnIsEmpty() {
        assertThat(store.findTurn("nope")).isEmpty();
    }

    @Test
    void saveIsIdempotent() {
        store.save(ContractTurns.T1);
        ConversationTurn changed = new ConversationTurn(ContractTurns.T1.turnId(), "shop", "c1", false,
                ContractTurns.T1.startedAt(), "u1", "acme", false, TurnOutcome.SUCCESS, null, 4321, null, List.of(),
                List.of(), List.of(), List.of(), null, "USD", Map.of(), null, null, List.of(), null);
        store.save(changed);
        assertThat(template.getCollection("ai_insights_turns").countDocuments()).isEqualTo(1);
        assertThat(store.findTurn("T1")).get().extracting(ConversationTurn::latencyMs).isEqualTo(4321L);
    }

    @Test
    void findTurnsFiltersAndSorts() {
        ContractTurns.all().forEach(store::save);
        var shop = store.findTurns(new TurnQuery("shop", ContractTurns.FROM, ContractTurns.TO, null, 100));
        assertThat(shop).extracting(ConversationTurn::turnId).containsExactly("T5", "T4", "T3", "T2", "T1", "T8");
        var acme = store.findTurns(new TurnQuery("shop", ContractTurns.FROM, ContractTurns.TO, "acme", 2));
        assertThat(acme).extracting(ConversationTurn::turnId).containsExactly("T5", "T2");
    }

    @Test
    void indexesCreatedOnFirstSave() {
        store.save(ContractTurns.T1);
        List<Document> indexes = template.getCollection("ai_insights_turns").listIndexes().into(new ArrayList<>());
        assertThat(indexes).extracting(i -> i.get("key", Document.class))
            .contains(new Document("application", 1).append("startedAt", -1),
                    new Document("application", 1).append("conversationId", 1).append("startedAt", 1),
                    new Document("application", 1).append("tenantId", 1).append("startedAt", -1),
                    new Document("application", 1).append("userId", 1).append("startedAt", 1));
        assertThat(ttl(indexes)).isEqualTo(NINETY_DAYS.toSeconds());
    }

    @Test
    void feedbackIsUpserted() {
        store.saveFeedback(ContractTurns.F2);
        store.saveFeedback(new Feedback("H2", "help", "h1", "v1", "acme", Rating.UP, null,
                Instant.parse("2026-10-01T11:06:00Z")));
        var feedback = template.getCollection("ai_insights_feedback");
        assertThat(feedback.countDocuments()).isEqualTo(1);
        Document doc = feedback.find().first();
        assertThat(doc.getString("_id")).isEqualTo("H2");
        assertThat(doc.getString("rating")).isEqualTo("UP");
        assertThat(doc.containsKey("comment")).isFalse();
        assertThat(doc.getInteger("schemaVersion")).isEqualTo(1);
        assertThat(store.conversationFeedback("help", "h1")).singleElement()
            .satisfies(f -> assertThat(f.rating()).isEqualTo(Rating.UP));
    }

    @Test
    void feedbackIndexesCreatedOnFirstSave() {
        store.saveFeedback(ContractTurns.F1);
        List<Document> indexes = template.getCollection("ai_insights_feedback").listIndexes()
            .into(new ArrayList<>());
        assertThat(indexes).extracting(i -> i.getString("name"))
            .contains("application_createdAt", "application_conversationId", "ttl_createdAt");
        assertThat(indexes).filteredOn(i -> "ttl_createdAt".equals(i.getString("name")))
            .singleElement()
            .satisfies(i -> assertThat(((Number) i.get("expireAfterSeconds")).longValue())
                .isEqualTo(NINETY_DAYS.toSeconds()));
    }

    @Test
    void retentionChangeUpdatesTtlIndex() {
        store.save(ContractTurns.T1);
        var shorter = new MongoEventStore(template, "ai_insights_", Duration.ofDays(30));
        shorter.save(ContractTurns.T2);
        List<Document> indexes = template.getCollection("ai_insights_turns").listIndexes().into(new ArrayList<>());
        assertThat(ttl(indexes)).isEqualTo(Duration.ofDays(30).toSeconds());
        assertThat(store.findTurn("T2")).isPresent();
    }

    @Test
    void collectionPrefixIsUsed() {
        new MongoEventStore(template, "x_", NINETY_DAYS).save(ContractTurns.T1);
        assertThat(template.collectionExists("x_turns")).isTrue();
        assertThat(template.collectionExists("ai_insights_turns")).isFalse();
    }

    @Test
    void retentionBelowOneMinuteIsRejected() {
        assertThatThrownBy(() -> new MongoEventStore(template, "p_", Duration.ofSeconds(59)))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void saveFailsQuicklyWhenMongoUnreachable() {
        var client = MongoClients.create("mongodb://localhost:1/?serverSelectionTimeoutMS=300");
        try {
            var down = new MongoEventStore(new MongoTemplate(client, "db"), "ai_insights_", NINETY_DAYS);
            long start = System.nanoTime();
            assertThatThrownBy(() -> down.save(ContractTurns.T1)).isInstanceOf(RuntimeException.class);
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(2));
        }
        finally {
            client.close();
        }
    }

    private static long ttl(List<Document> indexes) {
        return indexes.stream()
            .filter(i -> "ttl_startedAt".equals(i.getString("name")))
            .map(i -> ((Number) i.get("expireAfterSeconds")).longValue())
            .findFirst()
            .orElseThrow();
    }
}
