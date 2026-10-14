package io.github.springaiinsights.capture;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.github.springaiinsights.identity.InsightsContextKeys;
import io.github.springaiinsights.identity.InsightsIdentity;
import io.github.springaiinsights.identity.InsightsIdentityResolver;
import io.github.springaiinsights.identity.Pseudonymiser;
import io.github.springaiinsights.metrics.InsightsMetrics;
import io.github.springaiinsights.model.ConversationTurn;
import io.github.springaiinsights.model.TurnOutcome;
import io.github.springaiinsights.pricing.CostCalculator;
import io.github.springaiinsights.store.AsyncEventWriter;
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
import org.springframework.core.Ordered;

/**
 * Records one {@link ConversationTurn} per outermost {@code ChatClient} call. Runs outside chat memory and the tool
 * loop so a turn spans every model and tool call it causes; nested {@code ChatClient} calls fold into the outer turn.
 * Insights failures are counted and never affect the chat call.
 */
public final class InsightsAdvisor implements CallAdvisor, StreamAdvisor {

    public static final String NAME = "ai-insights";

    public static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 100;

    private static final String UNKNOWN = "unknown";

    private final ObservationRegistry observations;

    private final InsightsIdentityResolver identities;

    private final @Nullable Pseudonymiser pseudonymiser;

    private final CostCalculator costs;

    private final AsyncEventWriter writer;

    private final InsightsMetrics metrics;

    private final String application;

    private final boolean captureContent;

    public InsightsAdvisor(ObservationRegistry observations, InsightsIdentityResolver identities,
            @Nullable Pseudonymiser pseudonymiser, CostCalculator costs, AsyncEventWriter writer,
            InsightsMetrics metrics, String application, boolean captureContent) {
        this.observations = observations;
        this.identities = identities;
        this.pseudonymiser = pseudonymiser;
        this.costs = costs;
        this.writer = writer;
        this.metrics = metrics;
        this.application = application;
        this.captureContent = captureContent;
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
            close(turn.recorder(), request, TurnOutcome.ERROR, e, null, List.of(), null);
            throw e;
        }
        ChatResponse chatResponse = response.chatResponse();
        close(turn.recorder(), request, TurnOutcome.SUCCESS, null, chatResponse,
                chatResponse == null ? List.of() : finishReasons(chatResponse), text(chatResponse));
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
                        state.finishReasons, state.text == null ? null : state.text.toString()));
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
            TurnRecorder recorder = new TurnRecorder(UUID.randomUUID().toString(), application, Instant.now(),
                    startNanos, streaming, identity(request),
                    conversationIdMissing ? UUID.randomUUID().toString() : conversationId.toString(),
                    conversationIdMissing, costs);
            context.forEach((key, value) -> {
                if (key.startsWith(InsightsContextKeys.ATTRIBUTE_PREFIX) && value != null) {
                    recorder.putAttribute(key.substring(InsightsContextKeys.ATTRIBUTE_PREFIX.length()),
                            value.toString());
                }
            });
            ChatClientObservationContext chatClientContext = nearestChatClientContext(currentContext);
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

    private InsightsIdentity identity(ChatClientRequest request) {
        InsightsIdentity identity = identities.resolve(request);
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
            String text = text(chunk);
            if (text != null && !text.isEmpty()) {
                recorder.markFirstToken(System.nanoTime());
                if (captureContent) {
                    if (state.text == null) {
                        state.text = new StringBuilder();
                    }
                    state.text.append(text);
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
            @Nullable String assistantText) {
        try {
            long endNanos = System.nanoTime();
            if (captureContent) {
                recorder.setContent(request.prompt().getUserMessage().getText(), assistantText);
            }
            recorder.finishWhenIdle(outcome, error, reasons, endNanos, r -> addUncorrelatedUsage(r, last),
                    this::publish);
        }
        catch (RuntimeException e) {
            metrics.internalError();
        }
    }

    /** Without correlated model observations (e.g. a NOOP registry) the response usage is the only source. */
    private static void addUncorrelatedUsage(TurnRecorder recorder, @Nullable ChatResponse last) {
        if (recorder.hasModelCalls() || last == null || last.getMetadata() == null) {
            return;
        }
        ChatResponseMetadata metadata = last.getMetadata();
        Usage usage = metadata.getUsage();
        if (usage == null) {
            return;
        }
        String model = metadata.getModel();
        recorder.addModelCall(UNKNOWN, model == null || model.isBlank() ? UNKNOWN : model, usage.getPromptTokens(),
                usage.getCompletionTokens(), usage.getCacheReadInputTokens(), usage.getCacheWriteInputTokens(), 0,
                null);
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
    }
}
