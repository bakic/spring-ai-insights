package io.github.springaiinsights.store;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.function.Function;
import java.util.stream.Collectors;

import io.github.springaiinsights.analytics.Granularity;
import io.github.springaiinsights.analytics.InsightsAnalytics;
import io.github.springaiinsights.analytics.InsightsFilter;
import io.github.springaiinsights.analytics.ModelUsage;
import io.github.springaiinsights.analytics.RetrievalUsage;
import io.github.springaiinsights.analytics.TenantUsage;
import io.github.springaiinsights.analytics.TimeBucket;
import io.github.springaiinsights.analytics.ToolUsage;
import io.github.springaiinsights.analytics.UsageSummary;
import io.github.springaiinsights.analytics.UserUsage;
import io.github.springaiinsights.feedback.Feedback;
import io.github.springaiinsights.feedback.FeedbackStore;
import io.github.springaiinsights.model.ConversationTurn;
import io.github.springaiinsights.model.ModelCall;
import io.github.springaiinsights.model.RetrievalRecord;
import io.github.springaiinsights.model.ToolCallRecord;
import io.github.springaiinsights.model.ToolOutcome;
import io.github.springaiinsights.model.TurnOutcome;

/**
 * Bounded, process-local store. Keeps at most {@code maxTurns} turns and as many feedback entries, evicting the
 * oldest saved first. Data is lost on restart.
 */
public final class InMemoryEventStore implements InsightsEventStore, FeedbackStore, InsightsAnalytics {

    private final int maxTurns;

    private final Map<String, ConversationTurn> turns = new ConcurrentHashMap<>();

    private final ConcurrentLinkedDeque<String> order = new ConcurrentLinkedDeque<>();

    private final Map<String, Feedback> feedback = new ConcurrentHashMap<>();

    private final ConcurrentLinkedDeque<String> feedbackOrder = new ConcurrentLinkedDeque<>();

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
    public void saveFeedback(Feedback entry) {
        if (feedback.put(entry.turnId(), entry) == null) {
            feedbackOrder.addLast(entry.turnId());
        }
        while (feedback.size() > maxTurns) {
            String oldest = feedbackOrder.pollFirst();
            if (oldest == null) {
                break;
            }
            feedback.remove(oldest);
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

    @Override
    public UsageSummary summary(InsightsFilter filter) {
        List<ConversationTurn> selected = select(filter);
        List<ModelCall> calls = selected.stream().flatMap(t -> t.modelCalls().stream()).toList();
        List<RetrievalRecord> retrievals = selected.stream().flatMap(t -> t.retrievals().stream()).toList();
        List<Long> ttfts = selected.stream().map(ConversationTurn::timeToFirstTokenMs).filter(Objects::nonNull).toList();
        return new UsageSummary(selected.size(), distinct(selected, ConversationTurn::conversationId),
                distinct(selected, ConversationTurn::userId), selected.stream().filter(t -> t.userId() == null).count(),
                count(selected, TurnOutcome.ERROR), count(selected, TurnOutcome.CANCELLED),
                calls.stream().mapToLong(ModelCall::inputTokens).sum(),
                calls.stream().mapToLong(ModelCall::outputTokens).sum(),
                calls.stream().mapToLong(ModelCall::cacheReadTokens).sum(),
                calls.stream().mapToLong(ModelCall::cacheWriteTokens).sum(), cost(selected),
                selected.stream().filter(t -> t.modelCalls().stream().anyMatch(c -> c.cost() == null)).count(),
                selected.stream().mapToLong(ConversationTurn::latencyMs).average().orElse(0),
                ttfts.isEmpty() ? null : ttfts.stream().mapToLong(Long::longValue).average().getAsDouble(),
                selected.stream().mapToLong(t -> t.toolCalls().size()).sum(), retrievals.size(),
                retrievals.stream().filter(r -> r.resultCount() == 0).count());
    }

    @Override
    public List<TimeBucket> timeline(InsightsFilter filter, Granularity granularity) {
        ChronoUnit unit = granularity == Granularity.HOUR ? ChronoUnit.HOURS : ChronoUnit.DAYS;
        Map<Instant, List<ConversationTurn>> buckets = select(filter).stream()
            .collect(Collectors.groupingBy(
                    t -> ZonedDateTime.ofInstant(t.startedAt(), filter.zone()).truncatedTo(unit).toInstant(),
                    TreeMap::new, Collectors.toList()));
        return buckets.entrySet().stream()
            .map(e -> new TimeBucket(e.getKey(), e.getValue().size(),
                    distinct(e.getValue(), ConversationTurn::conversationId),
                    distinct(e.getValue(), ConversationTurn::userId), cost(e.getValue())))
            .toList();
    }

    @Override
    public List<ModelUsage> models(InsightsFilter filter) {
        Map<String, List<ModelCall>> byModel = select(filter).stream()
            .flatMap(t -> t.modelCalls().stream())
            .collect(Collectors.groupingBy(ModelCall::model));
        return byModel.entrySet().stream()
            .map(e -> {
                List<ModelCall> calls = e.getValue();
                return new ModelUsage(e.getKey(), calls.size(), calls.stream().mapToLong(ModelCall::inputTokens).sum(),
                        calls.stream().mapToLong(ModelCall::outputTokens).sum(),
                        calls.stream().mapToLong(ModelCall::cacheReadTokens).sum(),
                        calls.stream().mapToLong(ModelCall::cacheWriteTokens).sum(),
                        calls.stream().map(ModelCall::cost).filter(Objects::nonNull).reduce(BigDecimal.ZERO,
                                BigDecimal::add),
                        calls.stream().filter(c -> c.cost() == null).count());
            })
            .sorted(Comparator.comparingLong(ModelUsage::calls).reversed().thenComparing(ModelUsage::model))
            .toList();
    }

    @Override
    public List<ToolUsage> tools(InsightsFilter filter) {
        Map<String, List<ToolCallRecord>> byTool = new TreeMap<>();
        Map<String, Set<String>> turnsByTool = new TreeMap<>();
        for (ConversationTurn turn : select(filter)) {
            for (ToolCallRecord call : turn.toolCalls()) {
                byTool.computeIfAbsent(call.name(), k -> new ArrayList<>()).add(call);
                turnsByTool.computeIfAbsent(call.name(), k -> new HashSet<>()).add(turn.turnId());
            }
        }
        return byTool.entrySet().stream()
            .map(e -> {
                List<ToolCallRecord> calls = e.getValue();
                return new ToolUsage(e.getKey(), calls.size(),
                        calls.stream().filter(c -> c.outcome() == ToolOutcome.ERROR).count(),
                        turnsByTool.get(e.getKey()).size(),
                        calls.stream().mapToLong(ToolCallRecord::durationMs).average().orElse(0),
                        calls.stream().filter(c -> c.repeatIndex() > 0).count());
            })
            .sorted(Comparator.comparingLong(ToolUsage::calls).reversed().thenComparing(ToolUsage::tool))
            .toList();
    }

    @Override
    public List<RetrievalUsage> retrievals(InsightsFilter filter) {
        Map<String, List<RetrievalRecord>> byStore = select(filter).stream()
            .flatMap(t -> t.retrievals().stream())
            .collect(Collectors.groupingBy(RetrievalRecord::store));
        return byStore.entrySet().stream()
            .map(e -> {
                List<RetrievalRecord> queries = e.getValue();
                List<Double> topScores = queries.stream()
                    .filter(r -> !r.scores().isEmpty())
                    .map(r -> r.scores().stream().mapToDouble(Double::doubleValue).max().getAsDouble())
                    .toList();
                return new RetrievalUsage(e.getKey(), queries.size(),
                        queries.stream().filter(r -> r.resultCount() == 0).count(),
                        queries.stream().mapToInt(RetrievalRecord::resultCount).average().orElse(0),
                        topScores.isEmpty() ? null : topScores.stream().mapToDouble(Double::doubleValue).average()
                            .getAsDouble());
            })
            .sorted(Comparator.comparingLong(RetrievalUsage::queries).reversed().thenComparing(RetrievalUsage::store))
            .toList();
    }

    @Override
    public List<UserUsage> topUsers(InsightsFilter filter, int limit) {
        return select(filter).stream()
            .filter(t -> t.userId() != null)
            .collect(Collectors.groupingBy(ConversationTurn::userId))
            .entrySet()
            .stream()
            .map(e -> new UserUsage(e.getKey(), e.getValue().size(),
                    distinct(e.getValue(), ConversationTurn::conversationId), cost(e.getValue()),
                    e.getValue().stream().map(ConversationTurn::startedAt).max(Comparator.naturalOrder()).orElseThrow()))
            .sorted(Comparator.comparingLong(UserUsage::turns).reversed().thenComparing(UserUsage::userId))
            .limit(limit)
            .toList();
    }

    @Override
    public List<TenantUsage> tenants(InsightsFilter filter) {
        return select(filter).stream()
            .filter(t -> t.tenantId() != null)
            .collect(Collectors.groupingBy(ConversationTurn::tenantId))
            .entrySet()
            .stream()
            .map(e -> new TenantUsage(e.getKey(), e.getValue().size(), distinct(e.getValue(), ConversationTurn::userId),
                    cost(e.getValue())))
            .sorted(Comparator.comparingLong(TenantUsage::turns).reversed().thenComparing(TenantUsage::tenantId))
            .toList();
    }

    @Override
    public List<ConversationTurn> conversation(String application, String conversationId) {
        return turns.values().stream()
            .filter(t -> t.application().equals(application) && t.conversationId().equals(conversationId))
            .sorted(Comparator.comparing(ConversationTurn::startedAt))
            .limit(MAX_CONVERSATION_TURNS)
            .toList();
    }

    public List<Feedback> conversationFeedback(String application, String conversationId) {
        return feedback.values().stream()
            .filter(f -> f.application().equals(application) && f.conversationId().equals(conversationId))
            .sorted(Comparator.comparing(Feedback::createdAt).thenComparing(Feedback::turnId))
            .toList();
    }

    @Override
    public List<String> applications() {
        return turns.values().stream().map(ConversationTurn::application).distinct().sorted().toList();
    }

    private List<ConversationTurn> select(InsightsFilter filter) {
        return turns.values().stream()
            .filter(t -> filter.application() == null || filter.application().equals(t.application()))
            .filter(t -> !t.startedAt().isBefore(filter.from()) && t.startedAt().isBefore(filter.to()))
            .filter(t -> filter.tenantId() == null || filter.tenantId().equals(t.tenantId()))
            .toList();
    }

    private static long distinct(List<ConversationTurn> turns, Function<ConversationTurn, String> key) {
        return turns.stream().map(key).filter(Objects::nonNull).distinct().count();
    }

    private static long count(List<ConversationTurn> turns, TurnOutcome outcome) {
        return turns.stream().filter(t -> t.outcome() == outcome).count();
    }

    /** Sum of the priced model calls; unpriced calls contribute nothing. */
    private static BigDecimal cost(List<ConversationTurn> turns) {
        return turns.stream()
            .flatMap(t -> t.modelCalls().stream())
            .map(ModelCall::cost)
            .filter(Objects::nonNull)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
