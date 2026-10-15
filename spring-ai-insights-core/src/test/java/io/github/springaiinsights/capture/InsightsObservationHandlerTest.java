package io.github.springaiinsights.capture;

import java.io.IOException;
import java.util.List;

import io.github.springaiinsights.metrics.InsightsMetrics;
import io.github.springaiinsights.model.ConversationTurn;
import io.github.springaiinsights.model.RetrievalRecord;
import io.github.springaiinsights.model.ToolOutcome;
import io.github.springaiinsights.model.TurnOutcome;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.observation.ChatModelObservationContext;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.observation.ToolCallingObservationContext;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.observation.VectorStoreObservationContext;

import static org.assertj.core.api.Assertions.assertThat;

class InsightsObservationHandlerTest {

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    private final ObservationRegistry registry = ObservationRegistry.create();

    private final TurnRecorder recorder = TurnRecorderTest.recorder();

    private final Observation turn;

    InsightsObservationHandlerTest() {
        registry.observationConfig().observationHandler(new InsightsObservationHandler(new InsightsMetrics(meters, "app", false), "source"));
        turn = Observation.start("turn", registry);
        TurnRecorders.attach(turn.getContext(), recorder);
    }

    private ConversationTurn finish() {
        return recorder.finish(TurnOutcome.SUCCESS, null, List.of(), System.nanoTime()).orElseThrow();
    }

    private static ChatModelObservationContext chatContext(String requestModel) {
        return ChatModelObservationContext.builder()
            .prompt(new Prompt("hi", ChatOptions.builder().model(requestModel).build()))
            .provider("bedrock")
            .build();
    }

    private static ChatResponse response(String model, Usage usage) {
        return ChatResponse.builder()
            .generations(List.of(new Generation(new AssistantMessage("ok"))))
            .metadata(ChatResponseMetadata.builder().model(model).usage(usage).build())
            .build();
    }

    private void observe(Observation.Context context, Observation parent, Runnable body) {
        var obs = Observation.createNotStarted("child", () -> context, registry).parentObservation(parent).start();
        body.run();
        obs.stop();
    }

    @Test
    void attachesModelCallWithUsage() {
        var ctx = chatContext("req-model");
        observe(ctx, turn, () -> ctx.setResponse(response("m1", new DefaultUsage(100, 20, 120, null, 10L, null))));
        var call = finish().modelCalls().get(0);
        assertThat(call.provider()).isEqualTo("bedrock");
        assertThat(call.model()).isEqualTo("m1");
        assertThat(call.inputTokens()).isEqualTo(100);
        assertThat(call.outputTokens()).isEqualTo(20);
        assertThat(call.cacheReadTokens()).isEqualTo(10);
        assertThat(call.cacheWriteTokens()).isZero();
        assertThat(call.errorType()).isNull();
    }

    @Test
    void fallsBackToRequestModelWhenResponseMissing() {
        var ctx = chatContext("req-model");
        var obs = Observation.createNotStarted("child", () -> ctx, registry).parentObservation(turn).start();
        obs.error(new IllegalStateException("throttled"));
        obs.stop();
        var call = finish().modelCalls().get(0);
        assertThat(call.model()).isEqualTo("req-model");
        assertThat(call.inputTokens()).isZero();
        assertThat(call.errorType()).isEqualTo("IllegalStateException");
    }

    @Test
    void attachesToolCallAndError() {
        var ctx = ToolCallingObservationContext.builder()
            .toolDefinition(ToolDefinition.builder().name("weather").description("d").inputSchema("{}").build())
            .toolCallArguments("{}")
            .build();
        var obs = Observation.createNotStarted("tool", () -> ctx, registry).parentObservation(turn).start();
        obs.error(new IOException("down"));
        obs.stop();
        var tool = finish().toolCalls().get(0);
        assertThat(tool.name()).isEqualTo("weather");
        assertThat(tool.outcome()).isEqualTo(ToolOutcome.ERROR);
        assertThat(tool.errorType()).isEqualTo("IOException");
    }

    @Test
    void attachesQueryRetrievalOnly() {
        var query = VectorStoreObservationContext
            .builder("bedrock_knowledge_base", VectorStoreObservationContext.Operation.QUERY)
            .queryRequest(SearchRequest.builder().query("q").topK(4).build())
            .build();
        observe(query, turn, () -> query.setQueryResponse(List.of(
                Document.builder().id("d1").text("a").score(0.9).build(),
                Document.builder().id("d2").text("b").score(0.7).build())));
        var add = VectorStoreObservationContext
            .builder("bedrock_knowledge_base", VectorStoreObservationContext.Operation.ADD)
            .build();
        observe(add, turn, () -> { });
        var retrievals = finish().retrievals();
        assertThat(retrievals).hasSize(1);
        var r = retrievals.get(0);
        assertThat(r.store()).isEqualTo("bedrock_knowledge_base");
        assertThat(r.topK()).isEqualTo(4);
        assertThat(r.resultCount()).isEqualTo(2);
        assertThat(r.scores()).containsExactly(0.9, 0.7);
        assertThat(r.documentIds()).containsExactly("d1", "d2");
    }

    private static RetrievalRecord retrieve(@Nullable String documentKey, List<Document> documents) {
        ObservationRegistry own = ObservationRegistry.create();
        own.observationConfig()
            .observationHandler(new InsightsObservationHandler(
                    new InsightsMetrics(new SimpleMeterRegistry(), "app", false), documentKey));
        TurnRecorder ownRecorder = TurnRecorderTest.recorder();
        Observation ownTurn = Observation.start("turn", own);
        TurnRecorders.attach(ownTurn.getContext(), ownRecorder);
        var query = VectorStoreObservationContext
            .builder("kb", VectorStoreObservationContext.Operation.QUERY)
            .build();
        var obs = Observation.createNotStarted("q", () -> query, own).parentObservation(ownTurn).start();
        query.setQueryResponse(documents);
        obs.stop();
        return ownRecorder.finish(TurnOutcome.SUCCESS, null, List.of(), System.nanoTime())
            .orElseThrow()
            .retrievals()
            .get(0);
    }

    private static List<Document> chunks() {
        return List.of(
                Document.builder().id("c1").text("a").metadata("source", "s3://kb/a.pdf").score(0.9).build(),
                Document.builder().id("c2").text("b").metadata("source", "s3://kb/a.pdf").score(0.8).build(),
                Document.builder().id("c3").text("c").score(0.7).build());
    }

    @Test
    void retrievalUsesMetadataKeyDeduplicated() {
        RetrievalRecord r = retrieve("source", chunks());
        assertThat(r.documentIds()).containsExactly("s3://kb/a.pdf", "c3");
        assertThat(r.resultCount()).isEqualTo(3);
        assertThat(r.scores()).containsExactly(0.9, 0.8, 0.7);
    }

    @Test
    void blankKeyUsesDocumentIds() {
        assertThat(retrieve("", chunks()).documentIds()).containsExactly("c1", "c2", "c3");
        assertThat(retrieve(null, chunks()).documentIds()).containsExactly("c1", "c2", "c3");
    }

    @Test
    void nonStringMetadataIsStringified() {
        RetrievalRecord r = retrieve("source",
                List.of(Document.builder().id("c1").text("a").metadata("source", 42).build()));
        assertThat(r.documentIds()).containsExactly("42");
    }

    @Test
    void queryWithNullResponseIsEmptyRetrieval() {
        var query = VectorStoreObservationContext
            .builder("pgvector", VectorStoreObservationContext.Operation.QUERY)
            .build();
        observe(query, turn, () -> { });
        var r = finish().retrievals().get(0);
        assertThat(r.resultCount()).isZero();
        assertThat(r.topK()).isNull();
    }

    @Test
    void ignoresObservationsOutsideATurn() {
        var ctx = chatContext("m1");
        var lone = Observation.createNotStarted("lone", () -> ctx, registry).start();
        ctx.setResponse(response("m1", new DefaultUsage(1, 1)));
        lone.stop();
        assertThat(recorder.hasModelCalls()).isFalse();
        assertThat(meters.get("ai.insights.internal.errors").counter().count()).isZero();
    }

    @Test
    void handlerFailureIsSwallowed() {
        Usage broken = new DefaultUsage(1, 1) {
            @Override
            public Integer getPromptTokens() {
                throw new IllegalStateException("boom");
            }
        };
        var ctx = chatContext("m1");
        observe(ctx, turn, () -> ctx.setResponse(response("m1", broken)));
        assertThat(meters.get("ai.insights.internal.errors").counter().count()).isEqualTo(1);
        assertThat(recorder.hasModelCalls()).isFalse();
    }
}
