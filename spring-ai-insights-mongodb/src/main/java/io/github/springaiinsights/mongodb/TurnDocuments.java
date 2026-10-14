package io.github.springaiinsights.mongodb;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.github.springaiinsights.model.ConversationTurn;
import io.github.springaiinsights.model.ModelCall;
import io.github.springaiinsights.model.RetrievalRecord;
import io.github.springaiinsights.model.ToolCallRecord;
import io.github.springaiinsights.model.ToolOutcome;
import io.github.springaiinsights.model.TurnOutcome;
import org.bson.Document;
import org.bson.types.Decimal128;
import org.jspecify.annotations.Nullable;

/**
 * Maps turns to BSON documents (schema version 1). Costs are Decimal128 so they can be summed exactly; attributes are
 * a list of key/value pairs because their keys may contain '.' or '$'.
 */
final class TurnDocuments {

    static final int SCHEMA_VERSION = 1;

    private TurnDocuments() {
    }

    static Document toDocument(ConversationTurn turn) {
        Document doc = new Document("_id", turn.turnId()).append("schemaVersion", SCHEMA_VERSION)
            .append("application", turn.application())
            .append("conversationId", turn.conversationId())
            .append("conversationIdMissing", turn.conversationIdMissing())
            .append("startedAt", Date.from(turn.startedAt()))
            .append("userId", turn.userId())
            .append("tenantId", turn.tenantId())
            .append("streaming", turn.streaming())
            .append("outcome", turn.outcome().name())
            .append("errorType", turn.errorType())
            .append("latencyMs", turn.latencyMs())
            .append("timeToFirstTokenMs", turn.timeToFirstTokenMs())
            .append("finishReasons", turn.finishReasons())
            .append("modelCalls", turn.modelCalls().stream().map(TurnDocuments::modelCall).toList())
            .append("toolCalls", turn.toolCalls().stream().map(TurnDocuments::toolCall).toList())
            .append("retrievals", turn.retrievals().stream().map(TurnDocuments::retrieval).toList())
            .append("totalCost", decimal(turn.totalCost()))
            .append("currency", turn.currency())
            .append("attributes", turn.attributes().entrySet().stream()
                .map(e -> new Document("key", e.getKey()).append("value", e.getValue()))
                .toList());
        if (turn.userMessage() != null) {
            doc.append("userMessage", turn.userMessage());
        }
        if (turn.assistantMessage() != null) {
            doc.append("assistantMessage", turn.assistantMessage());
        }
        return doc;
    }

    static ConversationTurn fromDocument(Document doc) {
        Map<String, String> attributes = new HashMap<>();
        for (Document attribute : list(doc, "attributes")) {
            attributes.put(attribute.getString("key"), attribute.getString("value"));
        }
        return new ConversationTurn(doc.getString("_id"), doc.getString("application"), doc.getString("conversationId"),
                doc.getBoolean("conversationIdMissing", false), doc.getDate("startedAt").toInstant(),
                doc.getString("userId"), doc.getString("tenantId"), doc.getBoolean("streaming", false),
                TurnOutcome.valueOf(doc.getString("outcome")), doc.getString("errorType"), longValue(doc, "latencyMs"),
                nullableLong(doc, "timeToFirstTokenMs"), strings(doc, "finishReasons"),
                list(doc, "modelCalls").stream().map(TurnDocuments::modelCall).toList(),
                list(doc, "toolCalls").stream().map(TurnDocuments::toolCall).toList(),
                list(doc, "retrievals").stream().map(TurnDocuments::retrieval).toList(), bigDecimal(doc.get("totalCost")),
                doc.getString("currency"), attributes, doc.getString("userMessage"), doc.getString("assistantMessage"));
    }

    private static Document modelCall(ModelCall call) {
        return new Document("provider", call.provider()).append("model", call.model())
            .append("inputTokens", call.inputTokens())
            .append("outputTokens", call.outputTokens())
            .append("cacheReadTokens", call.cacheReadTokens())
            .append("cacheWriteTokens", call.cacheWriteTokens())
            .append("durationMs", call.durationMs())
            .append("cost", decimal(call.cost()))
            .append("errorType", call.errorType());
    }

    private static ModelCall modelCall(Document doc) {
        return new ModelCall(doc.getString("provider"), doc.getString("model"), longValue(doc, "inputTokens"),
                longValue(doc, "outputTokens"), longValue(doc, "cacheReadTokens"), longValue(doc, "cacheWriteTokens"),
                longValue(doc, "durationMs"), bigDecimal(doc.get("cost")), doc.getString("errorType"));
    }

    private static Document toolCall(ToolCallRecord call) {
        return new Document("name", call.name()).append("durationMs", call.durationMs())
            .append("outcome", call.outcome().name())
            .append("errorType", call.errorType())
            .append("repeatIndex", call.repeatIndex());
    }

    private static ToolCallRecord toolCall(Document doc) {
        return new ToolCallRecord(doc.getString("name"), longValue(doc, "durationMs"),
                ToolOutcome.valueOf(doc.getString("outcome")), doc.getString("errorType"),
                (int) longValue(doc, "repeatIndex"));
    }

    private static Document retrieval(RetrievalRecord retrieval) {
        return new Document("store", retrieval.store()).append("topK", retrieval.topK())
            .append("resultCount", retrieval.resultCount())
            .append("scores", retrieval.scores())
            .append("documentIds", retrieval.documentIds())
            .append("durationMs", retrieval.durationMs());
    }

    private static RetrievalRecord retrieval(Document doc) {
        Long topK = nullableLong(doc, "topK");
        List<Double> scores = new ArrayList<>();
        for (Object score : doc.getList("scores", Object.class, List.of())) {
            scores.add(((Number) score).doubleValue());
        }
        return new RetrievalRecord(doc.getString("store"), topK == null ? null : topK.intValue(),
                (int) longValue(doc, "resultCount"), scores, strings(doc, "documentIds"), longValue(doc, "durationMs"));
    }

    static @Nullable Decimal128 decimal(@Nullable BigDecimal value) {
        return value == null ? null : new Decimal128(value.round(MathContext.DECIMAL128));
    }

    static @Nullable BigDecimal bigDecimal(@Nullable Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Decimal128 decimal) {
            return decimal.bigDecimalValue();
        }
        return new BigDecimal(value.toString());
    }

    private static long longValue(Document doc, String key) {
        Object value = doc.get(key);
        return value == null ? 0 : ((Number) value).longValue();
    }

    private static @Nullable Long nullableLong(Document doc, String key) {
        Object value = doc.get(key);
        return value == null ? null : ((Number) value).longValue();
    }

    private static List<String> strings(Document doc, String key) {
        return doc.getList(key, String.class, List.of());
    }

    private static List<Document> list(Document doc, String key) {
        return doc.getList(key, Document.class, List.of());
    }
}
