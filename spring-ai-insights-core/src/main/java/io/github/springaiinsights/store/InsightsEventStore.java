package io.github.springaiinsights.store;

import java.util.List;
import java.util.Optional;

import io.github.springaiinsights.model.ConversationTurn;

/** Storage SPI for recorded turns. Implementations must be thread-safe. */
public interface InsightsEventStore {

    void save(ConversationTurn turn);

    Optional<ConversationTurn> findTurn(String turnId);

    /** Turns matching the query, newest first. */
    List<ConversationTurn> findTurns(TurnQuery query);
}
