package io.github.bakic.springaiinsights.capture;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;

import io.github.bakic.springaiinsights.identity.InsightsContextKeys;
import io.github.bakic.springaiinsights.identity.InsightsIdentity;
import io.github.bakic.springaiinsights.identity.InsightsIdentityResolver;
import io.github.bakic.springaiinsights.identity.Pseudonymiser;
import io.github.bakic.springaiinsights.metrics.InsightsMetrics;
import io.github.bakic.springaiinsights.model.ConversationTurn;
import io.github.bakic.springaiinsights.model.TurnOutcome;
import io.github.bakic.springaiinsights.pricing.CostCalculator;
import io.github.bakic.springaiinsights.store.AsyncEventWriter;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.ObservationView;
import io.micrometer.observation.contextpropagation.ObservationThreadLocalAccessor;
import org.jspecify.annotations.Nullable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.SignalType;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.client.observation.ChatClientObservationContext;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.core.Ordered;

/**
 * Records one {@link ConversationTurn} per outermost {@code ChatClient} call. Runs outside chat memory and the tool
 * loop so a turn spans every model and tool call it causes; nested {@code ChatClient} calls fold into the outer turn.
 * Insights failures are counted and never affect the chat call.
 */
public final class InsightsAdvisor implements CallAdvisor, StreamAdvisor {

    public static final String NAME = "ai-insights";

    public static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 100;

    /** Default cap, in characters, on the captured user message and on the captured answer. */
    public static final int DEFAULT_MAX_CONTENT_LENGTH = 32_768;

    /** Cap, in characters, on a conversation ID, which a caller may choose. */
    public static final int MAX_CONVERSATION_ID_LENGTH = 200;

    /** Caps, in characters, on the key and value of a turn attribute. */
    static final int MAX_ATTRIBUTE_KEY_LENGTH = 100;

    static final int MAX_ATTRIBUTE_VALUE_LENGTH = 500;

    private static final String UNKNOWN = "unknown";

    /** Marks a turn as published, so a stall timeout scheduled afterwards is cancelled at once. */
    private static final Future<?> DONE = CompletableFuture.completedFuture(null);

    private final ObservationRegistry observations;

    private final InsightsIdentityResolver identities;

    private final @Nullable Pseudonymiser pseudonymiser;

    private final CostCalculator costs;

    private final AsyncEventWriter writer;

    private final InsightsMetrics metrics;

    private final String application;

    private final boolean captureContent;

    private final int maxContentLength;

    private final TurnTimeouts timeouts;

    private final TraceIdSupplier traceIds;

    public InsightsAdvisor(ObservationRegistry observations, InsightsIdentityResolver identities,
            @Nullable Pseudonymiser pseudonymiser, CostCalculator costs, AsyncEventWriter writer,
            InsightsMetrics metrics, String application, boolean captureContent, TurnTimeouts timeouts,
            TraceIdSupplier traceIds) {
        this(observations, identities, pseudonymiser, costs, writer, metrics, application, captureContent,
                DEFAULT_MAX_CONTENT_LENGTH, timeouts, traceIds);
    }

    /**
     * @param maxContentLength the most characters kept of the user message and of the answer, when content is
     * captured
     */
    public InsightsAdvisor(ObservationRegistry observations, InsightsIdentityResolver identities,
            @Nullable Pseudonymiser pseudonymiser, CostCalculator costs, AsyncEventWriter writer,
            InsightsMetrics metrics, String application, boolean captureContent, int maxContentLength,
            TurnTimeouts timeouts, TraceIdSupplier traceIds) {
        if (maxContentLength < 1) {
            throw new IllegalArgumentException("maxContentLength must be at least 1: " + maxContentLength);
        }
        this.observations = observations;
        this.identities = identities;
        this.pseudonymiser = pseudonymiser;
        this.costs = costs;
        this.writer = writer;
        this.metrics = metrics;
        this.application = application;
        this.captureContent = captureContent;
        this.maxContentLength = maxContentLength;
        this.timeouts = timeouts;
        this.traceIds = traceIds;
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        Turn turn = open(request, observations.getCurrentObservation(), false);
        if (turn == null) {
            return chain.nextCall(request);
        }
        if (!turn.owner()) {
            return withTurnId(chain.nextCall(request), turn.recorder().turnId());
        }
        ChatClientResponse response;
        try {
            response = chain.nextCall(request);
        }
        catch (Throwable e) {
            close(turn.recorder(), request, TurnOutcome.ERROR, e, null, List.of(), null, null);
            throw e;
        }
        if (response == null) {
            close(turn.recorder(), request, TurnOutcome.SUCCESS, null, null, List.of(), null, null);
            return null;
        }
        ChatResponse chatResponse = response.chatResponse();
        close(turn.recorder(), request, TurnOutcome.SUCCESS, null, chatResponse,
                chatResponse == null ? List.of() : finishReasons(chatResponse), text(chatResponse), null);
        return withTurnId(response, turn.recorder().turnId());
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        return Flux.deferContextual(reactorContext -> {
            Observation current = reactorContext.getOrDefault(ObservationThreadLocalAccessor.KEY, null);
            Turn turn = open(request, current != null ? current : observations.getCurrentObservation(), true);
            if (turn == null) {
                return chain.nextStream(request);
            }
            String turnId = turn.recorder().turnId();
            if (!turn.owner()) {
                return chain.nextStream(request).map(r -> withTurnId(r, turnId));
            }
            StreamState state = new StreamState();
            return chain.nextStream(request)
                .map(response -> {
                    onChunk(turn.recorder(), state, response.chatResponse());
                    return withTurnId(response, turnId);
                })
                .doOnError(e -> state.error = e)
                .doFinally(signal -> close(turn.recorder(), request, outcome(signal), state.error, state.last,
                        state.finishReasons, state.text == null ? null : state.text.toString(), state.usage));
        });
    }

    private @Nullable Turn open(ChatClientRequest request, @Nullable Observation current, boolean streaming) {
        try {
            Observation.ContextView currentContext = current == null ? null : current.getContextView();
            TurnRecorder outer = TurnRecorders.outermost(currentContext);
            if (outer != null) {
                return new Turn(outer, false);
            }
            long startNanos = System.nanoTime();
            Map<String, @Nullable Object> context = request.context();
            Object conversationId = context.get(ChatMemory.CONVERSATION_ID);
            boolean conversationIdMissing = conversationId == null || conversationId.toString().isBlank();
            ChatClientObservationContext chatClientContext = nearestChatClientContext(currentContext);
            TurnRecorder recorder = new TurnRecorder(UUID.randomUUID().toString(), application, Instant.now(),
                    startNanos, streaming, identity(CallerIdentityCapture.captured(chatClientContext), request),
                    conversationIdMissing ? UUID.randomUUID().toString()
                            : truncate(conversationId.toString(), MAX_CONVERSATION_ID_LENGTH),
                    conversationIdMissing, costs);
            context.forEach((key, value) -> {
                if (key.startsWith(InsightsContextKeys.ATTRIBUTE_PREFIX) && value != null) {
                    recorder.putAttribute(
                            truncate(key.substring(InsightsContextKeys.ATTRIBUTE_PREFIX.length()),
                                    MAX_ATTRIBUTE_KEY_LENGTH),
                            truncate(value.toString(), MAX_ATTRIBUTE_VALUE_LENGTH));
                }
            });
            recorder.offeredTools(offeredTools(request));
            recorder.traceId(traceId(currentContext));
            if (chatClientContext != null) {
                TurnRecorders.attach(chatClientContext, recorder);
            }
            return new Turn(recorder, true);
        }
        catch (RuntimeException e) {
            metrics.internalError();
            return null;
        }
    }

    private List<String> offeredTools(ChatClientRequest request) {
        try {
            if (!(request.prompt().getOptions() instanceof ToolCallingChatOptions options)) {
                return List.of();
            }
            List<String> names = new ArrayList<>();
            for (ToolCallback callback : options.getToolCallbacks()) {
                names.add(callback.getToolDefinition().name());
            }
            return names;
        }
        catch (RuntimeException e) {
            metrics.internalError();
            return List.of();
        }
    }

    private @Nullable String traceId(Observation.@Nullable ContextView current) {
        try {
            return traceIds.traceId(current);
        }
        catch (RuntimeException e) {
            metrics.internalError();
            return null;
        }
    }

    private InsightsIdentity identity(@Nullable InsightsIdentity captured, ChatClientRequest request) {
        InsightsIdentity identity = captured != null ? captured : identities.resolve(request);
        if (identity == null) {
            return InsightsIdentity.ANONYMOUS;
        }
        if (pseudonymiser != null && identity.userId() != null) {
            return new InsightsIdentity(pseudonymiser.pseudonymise(identity.userId()), identity.tenantId());
        }
        return identity;
    }

    private void onChunk(TurnRecorder recorder, StreamState state, @Nullable ChatResponse chunk) {
        try {
            if (chunk == null) {
                return;
            }
            state.last = chunk;
            Usage usage = chunk.getMetadata() == null ? null : chunk.getMetadata().getUsage();
            if (usage != null && tokens(usage) > 0) {
                state.usage = usage;
            }
            String text = text(chunk);
            if (text != null && !text.isEmpty()) {
                recorder.markFirstToken(System.nanoTime());
                if (captureContent) {
                    if (state.text == null) {
                        state.text = new StringBuilder();
                    }
                    if (state.text.length() < maxContentLength) {
                        state.text.append(text);
                    }
                }
            }
            List<String> reasons = finishReasons(chunk);
            if (!reasons.isEmpty()) {
                state.finishReasons = reasons;
            }
        }
        catch (RuntimeException e) {
            metrics.internalError();
        }
    }

    private void close(TurnRecorder recorder, ChatClientRequest request, TurnOutcome outcome,
            @Nullable Throwable error, @Nullable ChatResponse last, List<String> reasons,
            @Nullable String assistantText, @Nullable Usage seenUsage) {
        try {
            long endNanos = System.nanoTime();
            if (captureContent) {
                recorder.setContent(truncate(request.prompt().getUserMessage().getText(), maxContentLength),
                        truncate(assistantText, maxContentLength));
            }
            AtomicReference<@Nullable Future<?>> timeout = new AtomicReference<>();
            boolean deferred = recorder.finishWhenIdle(outcome, error, reasons, endNanos, r -> {
                if (outcome != TurnOutcome.SUCCESS && seenUsage != null) {
                    r.fillUnreportedUsage(value(seenUsage.getPromptTokens()), value(seenUsage.getCompletionTokens()),
                            value(seenUsage.getCacheReadInputTokens()), value(seenUsage.getCacheWriteInputTokens()));
                }
                addUncorrelatedUsage(r, request, last);
            }, turn -> {
                cancel(timeout.getAndSet(DONE));
                publish(turn);
            });
            if (deferred) {
                Future<?> scheduled = timeouts.schedule(() -> {
                    try {
                        if (recorder.forceFinish()) {
                            metrics.turnStalled();
                        }
                    }
                    catch (RuntimeException e) {
                        metrics.internalError();
                    }
                });
                if (!timeout.compareAndSet(null, scheduled)) {
                    cancel(scheduled);
                }
            }
        }
        catch (RuntimeException e) {
            metrics.internalError();
        }
    }

    private static void cancel(@Nullable Future<?> timeout) {
        if (timeout != null && timeout != DONE) {
            timeout.cancel(false);
        }
    }

    /** Without correlated model observations (e.g. a NOOP registry) the response usage is the only source.
     * Some providers (e.g. Bedrock Converse) report no model in the response, so the request's model is the
     * fallback. */
    private static void addUncorrelatedUsage(TurnRecorder recorder, ChatClientRequest request,
            @Nullable ChatResponse last) {
        if (recorder.hasModelCalls() || last == null || last.getMetadata() == null) {
            return;
        }
        ChatResponseMetadata metadata = last.getMetadata();
        Usage usage = metadata.getUsage();
        if (usage == null) {
            return;
        }
        String model = metadata.getModel();
        if (model == null || model.isBlank()) {
            model = request.prompt().getOptions() == null ? null : request.prompt().getOptions().getModel();
        }
        recorder.addModelCall(UNKNOWN, model == null || model.isBlank() ? UNKNOWN : model, usage.getPromptTokens(),
                usage.getCompletionTokens(), usage.getCacheReadInputTokens(), usage.getCacheWriteInputTokens(), 0,
                null);
    }

    /** Usage on stream chunks is the turn total so far (ToolCallingAdvisor adds earlier rounds to it). */
    private static long tokens(Usage usage) {
        return value(usage.getPromptTokens()) + value(usage.getCompletionTokens());
    }

    private static long value(@Nullable Number tokens) {
        return tokens == null ? 0 : tokens.longValue();
    }

    private void publish(ConversationTurn turn) {
        try {
            metrics.turnRecorded(turn);
        }
        catch (RuntimeException e) {
            metrics.internalError();
        }
        writer.submit(turn);
    }

    private static @Nullable ChatClientObservationContext nearestChatClientContext(
            Observation.@Nullable ContextView start) {
        Observation.ContextView current = start;
        while (current != null) {
            if (current instanceof ChatClientObservationContext chatClientContext) {
                return chatClientContext;
            }
            ObservationView parent = current.getParentObservation();
            current = parent == null ? null : parent.getContextView();
        }
        return null;
    }

    /** At most {@code max} characters of {@code value}, without cutting a surrogate pair in two. */
    static @Nullable String truncate(@Nullable String value, int max) {
        if (value == null || value.length() <= max) {
            return value;
        }
        int end = max;
        if (end > 0 && Character.isHighSurrogate(value.charAt(end - 1))) {
            end--;
        }
        return value.substring(0, end);
    }

    private static ChatClientResponse withTurnId(ChatClientResponse response, String turnId) {
        return response.mutate().context(InsightsContextKeys.TURN_ID, turnId).build();
    }

    private static @Nullable String text(@Nullable ChatResponse response) {
        if (response == null || response.getResult() == null) {
            return null;
        }
        return response.getResult().getOutput().getText();
    }

    private static List<String> finishReasons(ChatResponse response) {
        List<String> reasons = new ArrayList<>();
        for (Generation generation : response.getResults()) {
            String reason = generation.getMetadata().getFinishReason();
            if (reason != null && !reason.isBlank()) {
                reasons.add(reason);
            }
        }
        return reasons;
    }

    private static TurnOutcome outcome(SignalType signal) {
        return switch (signal) {
            case ON_ERROR -> TurnOutcome.ERROR;
            case CANCEL -> TurnOutcome.CANCELLED;
            default -> TurnOutcome.SUCCESS;
        };
    }

    private record Turn(TurnRecorder recorder, boolean owner) {
    }

    private static final class StreamState {

        volatile @Nullable ChatResponse last;

        volatile @Nullable StringBuilder text;

        volatile @Nullable Throwable error;

        volatile List<String> finishReasons = List.of();

        volatile @Nullable Usage usage;
    }
}
