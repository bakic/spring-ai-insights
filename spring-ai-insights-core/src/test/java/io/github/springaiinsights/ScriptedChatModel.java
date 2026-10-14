package io.github.springaiinsights;

import java.time.Duration;
import java.util.List;
import java.util.function.Function;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.contextpropagation.ObservationThreadLocalAccessor;
import reactor.core.publisher.Flux;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.model.MessageAggregator;
import org.springframework.ai.chat.observation.ChatModelObservationContext;
import org.springframework.ai.chat.observation.ChatModelObservationDocumentation;
import org.springframework.ai.chat.observation.DefaultChatModelObservationConvention;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;

/**
 * Test chat model that answers from a script and emits chat-model observations the way Spring AI provider
 * models do (observe() for calls; a parent from the Reactor context and stop in doFinally for streams).
 */
public final class ScriptedChatModel implements ChatModel {

    private static final DefaultChatModelObservationConvention CONVENTION = new DefaultChatModelObservationConvention();

    private final ObservationRegistry registry;

    private final Function<Prompt, List<ChatResponse>> script;

    private volatile Duration chunkDelay = Duration.ZERO;

    private volatile Duration stopDelay = Duration.ZERO;

    public ScriptedChatModel(ObservationRegistry registry, Function<Prompt, List<ChatResponse>> script) {
        this.registry = registry;
        this.script = script;
    }

    public ScriptedChatModel chunkDelay(Duration delay) {
        this.chunkDelay = delay;
        return this;
    }

    /** Delays the stream observation stop, reproducing a provider that reports after the stream has ended. */
    public ScriptedChatModel stopDelay(Duration delay) {
        this.stopDelay = delay;
        return this;
    }

    public static ChatResponse text(String text, int inputTokens, int outputTokens) {
        return ChatResponse.builder()
            .generations(List.of(new Generation(new AssistantMessage(text),
                    ChatGenerationMetadata.builder().finishReason("STOP").build())))
            .metadata(ChatResponseMetadata.builder().model("m1").usage(new DefaultUsage(inputTokens, outputTokens)).build())
            .build();
    }

    public static ChatResponse chunk(String text) {
        return ChatResponse.builder().generations(List.of(new Generation(new AssistantMessage(text)))).build();
    }

    public static ChatResponse toolCall(String tool, String arguments) {
        var message = AssistantMessage.builder()
            .content("")
            .toolCalls(List.of(new AssistantMessage.ToolCall("call-" + tool, "function", tool, arguments)))
            .build();
        return ChatResponse.builder()
            .generations(List.of(new Generation(message, ChatGenerationMetadata.builder().finishReason("TOOL_CALLS").build())))
            .metadata(ChatResponseMetadata.builder().model("m1").usage(new DefaultUsage(10, 5)).build())
            .build();
    }

    public static long toolResponses(Prompt prompt) {
        return prompt.getInstructions().stream().filter(m -> m.getMessageType() == MessageType.TOOL).count();
    }

    public static String userText(Prompt prompt) {
        return prompt.getUserMessage().getText();
    }

    @Override
    public ChatOptions getOptions() {
        return ToolCallingChatOptions.builder().model("m1").build();
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        var context = context(prompt, false);
        return ChatModelObservationDocumentation.CHAT_MODEL_OPERATION
            .observation(null, CONVENTION, () -> context, registry)
            .observe(() -> {
                List<ChatResponse> responses = script.apply(prompt);
                ChatResponse response = responses.get(responses.size() - 1);
                context.setResponse(response);
                return response;
            });
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        return Flux.deferContextual(contextView -> {
            var context = context(prompt, true);
            Observation observation = ChatModelObservationDocumentation.CHAT_MODEL_OPERATION
                .observation(null, CONVENTION, () -> context, registry);
            observation.parentObservation(contextView.getOrDefault(ObservationThreadLocalAccessor.KEY, null)).start();
            Flux<ChatResponse> chunks = Flux.defer(() -> Flux.fromIterable(script.apply(prompt)));
            if (!chunkDelay.isZero()) {
                chunks = chunks.delayElements(chunkDelay);
            }
            Flux<ChatResponse> flux = chunks.doOnError(observation::error)
                .doFinally(s -> {
                    sleep(stopDelay);
                    observation.stop();
                })
                .contextWrite(ctx -> ctx.put(ObservationThreadLocalAccessor.KEY, observation));
            return new MessageAggregator().aggregate(flux, context::setResponse);
        });
    }

    private static void sleep(Duration delay) {
        try {
            Thread.sleep(delay);
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static ChatModelObservationContext context(Prompt prompt, boolean streaming) {
        return ChatModelObservationContext.builder().prompt(prompt).provider("scripted").streaming(streaming).build();
    }
}
