package io.github.bakic.springaiinsights.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import org.jspecify.annotations.Nullable;

/**
 * One user message and the assistant's answer, with everything that happened in between. {@code offeredTools} are
 * the names of the tools the model could call (sorted, distinct); {@code traceId} links the turn to its trace.
 */
public record ConversationTurn(String turnId, String application, String conversationId,
        boolean conversationIdMissing, Instant startedAt, @Nullable String userId,
        @Nullable String tenantId, boolean streaming, TurnOutcome outcome, @Nullable String errorType,
        long latencyMs, @Nullable Long timeToFirstTokenMs, List<String> finishReasons,
        List<ModelCall> modelCalls, List<ToolCallRecord> toolCalls, List<RetrievalRecord> retrievals,
        @Nullable BigDecimal totalCost, String currency, Map<String, String> attributes,
        @Nullable String userMessage, @Nullable String assistantMessage, List<String> offeredTools,
        @Nullable String traceId) {

    public ConversationTurn {
        finishReasons = List.copyOf(finishReasons);
        modelCalls = List.copyOf(modelCalls);
        toolCalls = List.copyOf(toolCalls);
        retrievals = List.copyOf(retrievals);
        attributes = Map.copyOf(attributes);
        offeredTools = List.copyOf(new TreeSet<>(offeredTools));
    }
}
