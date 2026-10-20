package io.github.bakic.springaiinsights;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import io.github.bakic.springaiinsights.model.ConversationTurn;
import io.github.bakic.springaiinsights.model.ModelCall;
import io.github.bakic.springaiinsights.model.RetrievalRecord;
import io.github.bakic.springaiinsights.model.ToolCallRecord;
import io.github.bakic.springaiinsights.model.TurnOutcome;

/** Test fixture building valid {@link ConversationTurn}s. */
public final class TestTurns {

    private String turnId = "turn-1";
    private String application = "app";
    private String conversationId = "conv-1";
    private Instant startedAt = Instant.parse("2026-10-01T10:00:00Z");
    private String userId;
    private String tenantId;
    private boolean streaming;
    private TurnOutcome outcome = TurnOutcome.SUCCESS;
    private Long ttftMs;
    private List<ModelCall> modelCalls = new ArrayList<>();
    private List<ToolCallRecord> toolCalls = new ArrayList<>();
    private List<RetrievalRecord> retrievals = new ArrayList<>();
    private BigDecimal totalCost;

    public static TestTurns minimal() {
        return new TestTurns();
    }

    public TestTurns withTurnId(String v) { this.turnId = v; return this; }
    public TestTurns withApplication(String v) { this.application = v; return this; }
    public TestTurns withConversationId(String v) { this.conversationId = v; return this; }
    public TestTurns withStartedAt(Instant v) { this.startedAt = v; return this; }
    public TestTurns withUserId(String v) { this.userId = v; return this; }
    public TestTurns withTenantId(String v) { this.tenantId = v; return this; }
    public TestTurns withStreaming(boolean v) { this.streaming = v; return this; }
    public TestTurns withOutcome(TurnOutcome v) { this.outcome = v; return this; }
    public TestTurns withTtftMs(Long v) { this.ttftMs = v; return this; }
    public TestTurns withModelCalls(List<ModelCall> v) { this.modelCalls = v; return this; }
    public TestTurns withToolCalls(List<ToolCallRecord> v) { this.toolCalls = v; return this; }
    public TestTurns withRetrievals(List<RetrievalRecord> v) { this.retrievals = v; return this; }
    public TestTurns withTotalCost(BigDecimal v) { this.totalCost = v; return this; }

    public ConversationTurn build() {
        return new ConversationTurn(turnId, application, conversationId, false, startedAt, userId, tenantId,
                streaming, outcome, null, 1000, ttftMs, List.of("STOP"), modelCalls, toolCalls, retrievals,
                totalCost, "USD", Map.of(), null, null, List.of(), null);
    }
}
