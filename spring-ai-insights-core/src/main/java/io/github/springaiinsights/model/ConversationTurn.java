package io.github.springaiinsights.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

/** One user message and the assistant's answer, with everything that happened in between. */
public record ConversationTurn(String turnId, String application, String conversationId,
        boolean conversationIdMissing, Instant startedAt, @Nullable String userId,
        @Nullable String tenantId, boolean streaming, TurnOutcome outcome, @Nullable String errorType,
        long latencyMs, @Nullable Long timeToFirstTokenMs, List<String> finishReasons,
        List<ModelCall> modelCalls, List<ToolCallRecord> toolCalls, List<RetrievalRecord> retrievals,
        @Nullable BigDecimal totalCost, String currency, Map<String, String> attributes,
        @Nullable String userMessage, @Nullable String assistantMessage) {

    public ConversationTurn {
        finishReasons = List.copyOf(finishReasons);
        modelCalls = List.copyOf(modelCalls);
        toolCalls = List.copyOf(toolCalls);
        retrievals = List.copyOf(retrievals);
        attributes = Map.copyOf(attributes);
    }
}
