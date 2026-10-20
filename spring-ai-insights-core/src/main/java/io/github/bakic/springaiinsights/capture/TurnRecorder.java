package io.github.bakic.springaiinsights.capture;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import io.github.bakic.springaiinsights.identity.InsightsIdentity;
import io.github.bakic.springaiinsights.model.ConversationTurn;
import io.github.bakic.springaiinsights.model.ModelCall;
import io.github.bakic.springaiinsights.model.RetrievalRecord;
import io.github.bakic.springaiinsights.model.ToolCallRecord;
import io.github.bakic.springaiinsights.model.ToolOutcome;
import io.github.bakic.springaiinsights.model.TurnOutcome;
import io.github.bakic.springaiinsights.pricing.CostCalculator;
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

    private volatile List<String> offeredTools = List.of();

    private volatile @Nullable String traceId;

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

    public void offeredTools(Collection<String> names) {
        this.offeredTools = List.copyOf(names);
    }

    public void traceId(@Nullable String traceId) {
        this.traceId = traceId;
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
     *
     * @return {@code true} if the finish was deferred until the open model calls end (see {@link #forceFinish()})
     */
    public boolean finishWhenIdle(TurnOutcome outcome, @Nullable Throwable error, List<String> finishReasons,
            long endNanos, Consumer<TurnRecorder> beforeFinish, Consumer<ConversationTurn> sink) {
        Runnable finish = () -> {
            beforeFinish.accept(this);
            finish(outcome, error, finishReasons, endNanos).ifPresent(sink);
        };
        synchronized (this) {
            if (openModelCalls > 0) {
                pendingFinish = finish;
                return true;
            }
        }
        finish.run();
        return false;
    }

    /**
     * Runs a deferred finish now, without waiting for the open model calls; later {@link #endModelCall()} calls do
     * nothing.
     *
     * @return {@code true} if a deferred finish ran
     */
    public boolean forceFinish() {
        Runnable finish;
        synchronized (this) {
            finish = pendingFinish;
            pendingFinish = null;
        }
        if (finish == null) {
            return false;
        }
        finish.run();
        return true;
    }

    /**
     * Streams that end early (cancel, error) report no usage on the model observation, although the advisor saw
     * usage in the chunks. Gives the last model call, if it recorded no tokens at all, the part of the usage seen
     * by the advisor that no model call accounts for. {@code input}..{@code cacheWrite} are turn totals.
     */
    public void fillUnreportedUsage(long input, long output, long cacheRead, long cacheWrite) {
        if (modelCalls.isEmpty()) {
            return;
        }
        int lastIndex = modelCalls.size() - 1;
        ModelCall last = modelCalls.get(lastIndex);
        if (last.inputTokens() + last.outputTokens() + last.cacheReadTokens() + last.cacheWriteTokens() != 0) {
            return;
        }
        long in = Math.max(0, input - modelCalls.stream().mapToLong(ModelCall::inputTokens).sum());
        long out = Math.max(0, output - modelCalls.stream().mapToLong(ModelCall::outputTokens).sum());
        long read = Math.max(0, cacheRead - modelCalls.stream().mapToLong(ModelCall::cacheReadTokens).sum());
        long write = Math.max(0, cacheWrite - modelCalls.stream().mapToLong(ModelCall::cacheWriteTokens).sum());
        if (in + out + read + write == 0) {
            return;
        }
        modelCalls.set(lastIndex, new ModelCall(last.provider(), last.model(), in, out, read, write, last.durationMs(),
                costs.cost(last.model(), in, out, read, write), last.errorType()));
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
                totalCost(calls), costs.currency(), Map.copyOf(attributes), userMessage, assistantMessage,
                offeredTools, traceId));
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
