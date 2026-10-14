package io.github.springaiinsights.store;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

import io.github.springaiinsights.model.ConversationTurn;

/** Bounded, process-local store. Evicts the oldest saved turn when full. Data is lost on restart. */
public final class InMemoryEventStore implements InsightsEventStore {

    private final int maxTurns;

    private final Map<String, ConversationTurn> turns = new ConcurrentHashMap<>();

    private final ConcurrentLinkedDeque<String> order = new ConcurrentLinkedDeque<>();

    public InMemoryEventStore(int maxTurns) {
        if (maxTurns < 1) {
            throw new IllegalArgumentException("maxTurns must be at least 1");
        }
        this.maxTurns = maxTurns;
    }

    @Override
    public void save(ConversationTurn turn) {
        if (turns.put(turn.turnId(), turn) == null) {
            order.addLast(turn.turnId());
        }
        while (turns.size() > maxTurns) {
            String oldest = order.pollFirst();
            if (oldest == null) {
                break;
            }
            turns.remove(oldest);
        }
    }

    @Override
    public Optional<ConversationTurn> findTurn(String turnId) {
        return Optional.ofNullable(turns.get(turnId));
    }

    @Override
    public List<ConversationTurn> findTurns(TurnQuery query) {
        return turns.values().stream()
            .filter(t -> t.application().equals(query.application()))
            .filter(t -> !t.startedAt().isBefore(query.from()) && t.startedAt().isBefore(query.to()))
            .filter(t -> query.tenantId() == null || query.tenantId().equals(t.tenantId()))
            .sorted(Comparator.comparing(ConversationTurn::startedAt).reversed())
            .limit(query.limit())
            .toList();
    }
}
