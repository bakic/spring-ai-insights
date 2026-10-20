package io.github.bakic.springaiinsights.web.pages;

import java.math.BigDecimal;
import java.time.Instant;

import io.github.bakic.springaiinsights.model.ConversationTurn;
import io.github.bakic.springaiinsights.model.TurnOutcome;
import org.jspecify.annotations.Nullable;

/** One line of the recent-turns table. */
public record TurnRow(String turnId, String conversationId, Instant startedAt, @Nullable String userId,
        @Nullable String tenantId, TurnOutcome outcome, long latencyMs, @Nullable BigDecimal cost, int toolCalls,
        int retrievals) {

    public static TurnRow of(ConversationTurn turn) {
        return new TurnRow(turn.turnId(), turn.conversationId(), turn.startedAt(), turn.userId(), turn.tenantId(),
                turn.outcome(), turn.latencyMs(), turn.totalCost(), turn.toolCalls().size(),
                turn.retrievals().size());
    }
}
