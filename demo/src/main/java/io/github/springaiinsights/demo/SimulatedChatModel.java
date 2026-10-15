package io.github.springaiinsights.demo;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.contextpropagation.ObservationThreadLocalAccessor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
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
import org.springframework.stereotype.Component;

/**
 * A chat model that answers from {@link HelpdeskScript} and emits the same observations as Spring AI's provider
 * models, so Spring AI Insights records it like a real one. No provider is called.
 */
@Component
class SimulatedChatModel implements ChatModel {

    private static final DefaultChatModelObservationConvention CONVENTION = new DefaultChatModelObservationConvention();

    private final ObservationRegistry registry;

    private final HelpdeskScript script = new HelpdeskScript();

    SimulatedChatModel(ObservationRegistry registry) {
        this.registry = registry;
    }

    @Override
    public ChatOptions getOptions() {
        return ToolCallingChatOptions.builder().model(HelpdeskScript.CHAT_MODEL).build();
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        var context = context(prompt, false);
        return ChatModelObservationDocumentation.CHAT_MODEL_OPERATION
            .observation(null, CONVENTION, () -> context, registry)
            .observe(() -> {
                ChatResponse response = script.respond(prompt);
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
            Flux<ChatResponse> flux = Flux.defer(() -> Flux.fromIterable(chunks(script.respond(prompt))))
                .concatMap(chunk -> Mono.just(chunk).delayElement(Duration.ofMillis(ThreadLocalRandom.current().nextInt(15, 41))))
                .doOnError(observation::error)
                .doFinally(signal -> observation.stop())
                .contextWrite(ctx -> ctx.put(ObservationThreadLocalAccessor.KEY, observation));
            return new MessageAggregator().aggregate(flux, context::setResponse);
        });
    }

    /** One chunk per word; the last one carries the finish reason, the model and the usage, as providers do. */
    static List<ChatResponse> chunks(ChatResponse response) {
        if (response.getResult().getOutput().hasToolCalls()) {
            return List.of(response);
        }
        String[] words = response.getResult().getOutput().getText().split("(?<= )");
        List<ChatResponse> chunks = new ArrayList<>();
        for (int i = 0; i < words.length - 1; i++) {
            chunks.add(ChatResponse.builder().generations(List.of(new Generation(new AssistantMessage(words[i])))).build());
        }
        chunks.add(ChatResponse.builder()
            .generations(List.of(new Generation(new AssistantMessage(words[words.length - 1]),
                    ChatGenerationMetadata.builder().finishReason(response.getResult().getMetadata().getFinishReason()).build())))
            .metadata(response.getMetadata())
            .build());
        return chunks;
    }

    private static ChatModelObservationContext context(Prompt prompt, boolean streaming) {
        return ChatModelObservationContext.builder().prompt(prompt).provider("simulated").streaming(streaming).build();
    }
}
