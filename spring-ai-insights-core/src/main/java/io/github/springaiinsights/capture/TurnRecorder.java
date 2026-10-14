package io.github.springaiinsights.capture;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import io.github.springaiinsights.identity.InsightsIdentity;
import io.github.springaiinsights.model.ConversationTurn;
import io.github.springaiinsights.model.ModelCall;
import io.github.springaiinsights.model.RetrievalRecord;
import io.github.springaiinsights.model.ToolCallRecord;
import io.github.springaiinsights.model.ToolOutcome;
import io.github.springaiinsights.model.TurnOutcome;
import io.github.springaiinsights.pricing.CostCalculator;
import org.jspecify.annotations.Nullable;

/** Collects everything that happens during one turn, from any thread, then builds the immutable turn once. */
public final class TurnRecorder {

    private static final long NOT_MARKED = Long.MIN_VALUE;

    private final String turnId;

    private final String application;

    private final Instant startedAt;

    private final long startNanos;

    private final boolean streaming;

    private final InsightsIdentity identity;

    private final String conversationId;

    private final boolean conversationIdMissing;

    private final CostCalculator costs;

    private final List<ModelCall> modelCalls = new CopyOnWriteArrayList<>();

    private final List<ToolCallRecord> toolCalls = new ArrayList<>();

    private final List<RetrievalRecord> retrievals = new CopyOnWriteArrayList<>();

    private final Map<String, String> attributes = new ConcurrentHashMap<>();

    private final AtomicLong firstTokenNanos = new AtomicLong(NOT_MARKED);

    private final AtomicBoolean finished = new AtomicBoolean();

    private volatile @Nullable String userMessage;

    private int openModelCalls;

    private @Nullable Runnable pendingFinish;

    private volatile @Nullable String assistantMessage;

    public TurnRecorder(String turnId, String application, Instant startedAt, long startNanos, boolean streaming,
            InsightsIdentity identity, String conversationId, boolean conversationIdMissing, CostCalculator costs) {
        this.turnId = turnId;
        this.application = application;
        this.startedAt = startedAt;
        this.startNanos = startNanos;
        this.streaming = streaming;
        this.identity = identity;
        this.conversationId = conversationId;
        this.conversationIdMissing = conversationIdMissing;
        this.costs = costs;
    }

    public String turnId() {
        return turnId;
    }

    public void addModelCall(String provider, String model, @Nullable Integer inputTokens,
            @Nullable Integer outputTokens, @Nullable Long cacheRead, @Nullable Long cacheWrite, long durationMs,
            @Nullable Throwable error) {
        long in = inputTokens == null ? 0 : inputTokens;
        long out = outputTokens == null ? 0 : outputTokens;
        long read = cacheRead == null ? 0 : cacheRead;
        long write = cacheWrite == null ? 0 : cacheWrite;
        BigDecimal cost = costs.cost(model, in, out, read, write);
        modelCalls.add(new ModelCall(provider, model, in, out, read, write, durationMs, cost, errorType(error)));
    }

    public void addToolCall(String name, long durationMs, @Nullable Throwable error) {
        synchronized (toolCalls) {
            int repeatIndex = (int) toolCalls.stream().filter(t -> t.name().equals(name)).count();
            toolCalls.add(new ToolCallRecord(name, durationMs, error == null ? ToolOutcome.SUCCESS : ToolOutcome.ERROR,
                    errorType(error), repeatIndex));
        }
    }

    public void addRetrieval(RetrievalRecord retrieval) {
        retrievals.add(retrieval);
    }

    public void markFirstToken(long nanos) {
        firstTokenNanos.compareAndSet(NOT_MARKED, nanos);
    }

    public void putAttribute(String key, String value) {
        attributes.put(key, value);
    }

    public void setContent(@Nullable String userMessage, @Nullable String assistantMessage) {
        this.userMessage = userMessage;
        this.assistantMessage = assistantMessage;
    }

    /** A model call of this turn has started; the turn will not finish until it ends. */
    public synchronized void beginModelCall() {
        openModelCalls++;
    }

    /** A model call started with {@link #beginModelCall()} has ended (after {@link #addModelCall}). */
    public void endModelCall() {
        Runnable finish;
        synchronized (this) {
            if (openModelCalls > 0) {
                openModelCalls--;
            }
            if (openModelCalls > 0 || pendingFinish == null) {
                return;
            }
            finish = pendingFinish;
            pendingFinish = null;
        }
        finish.run();
    }

    /**
     * Finishes the turn as soon as no model call is open. Streaming providers stop their observation after the
     * stream has terminated downstream, so the advisor sees the end of the stream before the last model call is
     * reported. {@code beforeFinish} runs just before the turn is built; {@code sink} receives it.
     */
    public void finishWhenIdle(TurnOutcome outcome, @Nullable Throwable error, List<String> finishReasons,
            long endNanos, Consumer<TurnRecorder> beforeFinish, Consumer<ConversationTurn> sink) {
        Runnable finish = () -> {
            beforeFinish.accept(this);
            finish(outcome, error, finishReasons, endNanos).ifPresent(sink);
        };
        synchronized (this) {
            if (openModelCalls > 0) {
                pendingFinish = finish;
                return;
            }
        }
        finish.run();
    }

    public boolean hasModelCalls() {
        return !modelCalls.isEmpty();
    }

    public Optional<ConversationTurn> finish(TurnOutcome outcome, @Nullable Throwable error,
            List<String> finishReasons, long endNanos) {
        if (!finished.compareAndSet(false, true)) {
            return Optional.empty();
        }
        List<ModelCall> calls = List.copyOf(modelCalls);
        List<ToolCallRecord> tools;
        synchronized (toolCalls) {
            tools = List.copyOf(toolCalls);
        }
        long first = firstTokenNanos.get();
        Long ttft = first == NOT_MARKED ? null : toMillis(first - startNanos);
        return Optional.of(new ConversationTurn(turnId, application, conversationId, conversationIdMissing,
                startedAt, identity.userId(), identity.tenantId(), streaming, outcome, errorType(error),
                toMillis(endNanos - startNanos), ttft, finishReasons, calls, tools, List.copyOf(retrievals),
                totalCost(calls), costs.currency(), Map.copyOf(attributes), userMessage, assistantMessage));
    }

    private static @Nullable BigDecimal totalCost(List<ModelCall> calls) {
        if (calls.isEmpty()) {
            return null;
        }
        BigDecimal total = BigDecimal.ZERO;
        for (ModelCall call : calls) {
            if (call.cost() == null) {
                return null;
            }
            total = total.add(call.cost());
        }
        return total;
    }

    private static long toMillis(long nanos) {
        return Math.max(0, nanos / 1_000_000L);
    }

    private static @Nullable String errorType(@Nullable Throwable error) {
        return error == null ? null : error.getClass().getSimpleName();
    }
}
