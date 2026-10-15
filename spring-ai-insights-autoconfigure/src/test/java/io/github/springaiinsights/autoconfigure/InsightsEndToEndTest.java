package io.github.springaiinsights.autoconfigure;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import io.github.springaiinsights.ScriptedChatModel;
import io.github.springaiinsights.identity.InsightsContextKeys;
import io.github.springaiinsights.identity.Pseudonymiser;
import io.github.springaiinsights.model.ConversationTurn;
import io.github.springaiinsights.model.ModelCall;
import io.github.springaiinsights.model.ToolCallRecord;
import io.github.springaiinsights.model.ToolOutcome;
import io.github.springaiinsights.store.InMemoryEventStore;
import io.github.springaiinsights.store.InsightsEventStore;
import io.github.springaiinsights.store.TurnQuery;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import software.amazon.awssdk.services.bedrockagentruntime.BedrockAgentRuntimeClient;
import software.amazon.awssdk.services.bedrockagentruntime.model.KnowledgeBaseRetrievalResult;
import software.amazon.awssdk.services.bedrockagentruntime.model.RetrieveRequest;
import software.amazon.awssdk.services.bedrockagentruntime.model.RetrieveResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest(properties = { "ai-insights.identity.secret=k", "spring.application.name=e2e" })
class InsightsEndToEndTest {

    static final AtomicBoolean FAIL_SAVES = new AtomicBoolean();

    @Autowired
    ChatClient.Builder builder;

    @Autowired
    InsightsEventStore store;

    @Autowired
    MeterRegistry meters;

    @Autowired
    Tools tools;

    @Autowired
    SimpleVectorStore vectorStore;

    @AfterEach
    void reset() {
        FAIL_SAVES.set(false);
        SecurityContextHolder.clearContext();
    }

    @Test
    void autoConfiguredChatClientRecordsToolAndRetrieval() {
        double turnsBefore = count("ai.insights.turns");

        ChatClientResponse response = builder.build().prompt().user("use lookup").tools(tools).call().chatClientResponse();

        assertThat(response.chatResponse().getResult().getOutput().getText()).isEqualTo("answer");
        ConversationTurn turn = recorded(response);
        assertThat(turn.application()).isEqualTo("e2e");
        assertThat(turn.toolCalls()).extracting(ToolCallRecord::name).containsExactly("lookup");
        assertThat(turn.retrievals()).singleElement().satisfies(r -> {
            assertThat(r.store()).isEqualTo("simple");
            assertThat(r.resultCount()).isEqualTo(2);
        });
        assertThat(turn.modelCalls()).hasSize(2).extracting(ModelCall::provider).containsOnly("scripted");
        await().atMost(Duration.ofSeconds(5)).until(() -> count("ai.insights.turns") - turnsBefore == 1);
    }

    @Test
    void failingToolIsRecordedAsError() {
        ChatClientResponse response = builder.build().prompt().user("use fail").tools(tools).call().chatClientResponse();

        assertThat(response.chatResponse().getResult().getOutput().getText()).isEqualTo("answer");
        assertThat(recorded(response).toolCalls()).singleElement().satisfies(call -> {
            assertThat(call.name()).isEqualTo("fail");
            assertThat(call.outcome()).isEqualTo(ToolOutcome.ERROR);
            assertThat(call.errorType()).isEqualTo("IllegalStateException");
        });
    }

    @Test
    void unobservedKnowledgeBaseIsRecorded() {
        ChatClientResponse response = builder.build().prompt().user("use kb").tools(tools).call().chatClientResponse();

        ConversationTurn turn = recorded(response);
        assertThat(turn.toolCalls()).extracting(ToolCallRecord::name).containsExactly("kb");
        assertThat(turn.retrievals()).singleElement().satisfies(r -> {
            assertThat(r.store()).isEqualTo("fake_knowledge_base");
            assertThat(r.resultCount()).isEqualTo(2);
            assertThat(r.documentIds()).containsExactly("s3://kb/refunds.pdf");
        });
    }

    @Test
    void knowledgeBaseSearchedWithTheAwsSdkIsRecorded() {
        ChatClientResponse response = builder.build().prompt().user("use bedrock").tools(tools).call().chatClientResponse();

        ConversationTurn turn = recorded(response);
        assertThat(turn.toolCalls()).extracting(ToolCallRecord::name).containsExactly("bedrock");
        assertThat(turn.retrievals()).singleElement().satisfies(r -> {
            assertThat(r.store()).isEqualTo("bedrock_knowledge_base");
            assertThat(r.topK()).isEqualTo(5);
            assertThat(r.resultCount()).isEqualTo(2);
            assertThat(r.documentIds()).containsExactly("s3://kb/invoices.pdf", "s3://kb/payments.pdf");
        });
    }

    @Test
    void chatWorksWhenStoreThrows() {
        double errorsBefore = count("ai.insights.store.errors");
        FAIL_SAVES.set(true);

        String answer = builder.build().prompt().user("hello").call().content();

        assertThat(answer).isEqualTo("answer");
        await().atMost(Duration.ofSeconds(5)).until(() -> count("ai.insights.store.errors") - errorsBefore >= 1);
    }

    @Test
    void concurrentTurnsDoNotMixToolCalls() throws Exception {
        ChatClient client = builder.build();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<CompletableFuture<String[]>> futures = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                String tool = i % 2 == 0 ? "a" : "b";
                futures.add(CompletableFuture.supplyAsync(() -> {
                    ChatClientResponse response = client.prompt().user("use " + tool).tools(tools).call().chatClientResponse();
                    return new String[] { tool, (String) response.context().get(InsightsContextKeys.TURN_ID) };
                }, pool));
            }
            for (CompletableFuture<String[]> future : futures) {
                String[] result = future.get();
                ConversationTurn turn = await().atMost(Duration.ofSeconds(5))
                    .until(() -> store.findTurn(result[1]), Optional::isPresent)
                    .orElseThrow();
                assertThat(turn.toolCalls()).extracting(ToolCallRecord::name).containsExactly(result[0]);
                assertThat(turn.modelCalls()).hasSize(2);
            }
        }
        finally {
            pool.shutdownNow();
        }
    }

    @Test
    void concurrentStreamsDoNotMixToolCalls() {
        ChatClient client = builder.build();
        List<String[]> results = Flux.range(0, 20)
            .flatMap(i -> {
                String tool = i % 2 == 0 ? "a" : "b";
                return client.prompt()
                    .user("use " + tool)
                    .tools(tools)
                    .stream()
                    .chatClientResponse()
                    .last()
                    .map(r -> new String[] { tool, (String) r.context().get(InsightsContextKeys.TURN_ID) })
                    .subscribeOn(Schedulers.parallel());
            }, 20)
            .collectList()
            .block(Duration.ofSeconds(30));

        assertThat(results).hasSize(20);
        for (String[] result : results) {
            ConversationTurn turn = await().atMost(Duration.ofSeconds(5))
                .until(() -> store.findTurn(result[1]), Optional::isPresent)
                .orElseThrow();
            assertThat(turn.streaming()).isTrue();
            assertThat(turn.toolCalls()).extracting(ToolCallRecord::name).containsExactly(result[0]);
            assertThat(turn.modelCalls()).hasSize(2);
        }
    }

    @Test
    void anonymousTurnHasNullUser() {
        ConversationTurn turn = recorded(builder.build().prompt().user("hello").call().chatClientResponse());

        assertThat(turn.userId()).isNull();
        assertThat(turn.tenantId()).isNull();
    }

    @Test
    void securityPrincipalIsPseudonymised() {
        SecurityContextHolder.getContext()
            .setAuthentication(UsernamePasswordAuthenticationToken.authenticated("alice", null, List.of()));

        ConversationTurn turn = recorded(builder.build().prompt().user("hello").call().chatClientResponse());

        assertThat(turn.userId())
            .isEqualTo(new Pseudonymiser("k".getBytes(StandardCharsets.UTF_8)).pseudonymise("alice"))
            .isNotEqualTo("alice");
    }

    @Test
    void streamedTurnIsRecordedWithTool() {
        List<ChatClientResponse> chunks = builder.build()
            .prompt()
            .user("use lookup")
            .tools(tools)
            .stream()
            .chatClientResponse()
            .collectList()
            .block(Duration.ofSeconds(10));

        String turnId = (String) chunks.get(0).context().get(InsightsContextKeys.TURN_ID);
        ConversationTurn turn = await().atMost(Duration.ofSeconds(5))
            .until(() -> store.findTurn(turnId), Optional::isPresent)
            .orElseThrow();
        assertThat(turn.streaming()).isTrue();
        assertThat(turn.toolCalls()).extracting(ToolCallRecord::name).containsExactly("lookup");
        assertThat(turn.retrievals()).hasSize(1);
        assertThat(turn.modelCalls()).hasSize(2);
    }

    @Test
    void questionAnswerAdvisorSearchIsRecordedOnACalledTurn() {
        ChatClientResponse response = builder.build()
            .prompt()
            .user("hello")
            .advisors(QuestionAnswerAdvisor.builder(vectorStore).build())
            .call()
            .chatClientResponse();

        assertThat(recorded(response).retrievals()).singleElement()
            .satisfies(r -> assertThat(r.store()).isEqualTo("simple"));
    }

    @Test
    void questionAnswerAdvisorSearchIsRecordedOnAStreamedTurn() {
        List<ChatClientResponse> chunks = builder.build()
            .prompt()
            .user("hello")
            .advisors(QuestionAnswerAdvisor.builder(vectorStore).build())
            .stream()
            .chatClientResponse()
            .collectList()
            .block(Duration.ofSeconds(10));

        ConversationTurn turn = recorded(chunks.get(0));
        assertThat(turn.streaming()).isTrue();
        assertThat(turn.retrievals()).singleElement()
            .satisfies(r -> assertThat(r.store()).isEqualTo("simple"));
    }

    private ConversationTurn recorded(ChatClientResponse response) {
        String turnId = (String) response.context().get(InsightsContextKeys.TURN_ID);
        assertThat(turnId).isNotBlank();
        return await().atMost(Duration.ofSeconds(5)).until(() -> store.findTurn(turnId), Optional::isPresent).orElseThrow();
    }

    private double count(String name) {
        return meters.find(name).counters().stream().mapToDouble(c -> c.count()).sum();
    }

    static List<ChatResponse> script(Prompt prompt) {
        String text = ScriptedChatModel.userText(prompt);
        if (text.startsWith("use ") && ScriptedChatModel.toolResponses(prompt) == 0) {
            String tool = text.substring(4);
            return List.of(ScriptedChatModel.toolCall(tool, tool.equals("lookup") || tool.equals("kb") || tool.equals("bedrock") ? "{\"query\":\"refunds\"}" : "{}"));
        }
        return List.of(ScriptedChatModel.text("answer", 20, 10));
    }

    public static class Tools {

        private final SimpleVectorStore vectorStore;

        private final VectorStore knowledgeBase;

        private final BedrockAgentRuntimeClient bedrock;

        Tools(SimpleVectorStore vectorStore, VectorStore knowledgeBase, BedrockAgentRuntimeClient bedrock) {
            this.vectorStore = vectorStore;
            this.knowledgeBase = knowledgeBase;
            this.bedrock = bedrock;
        }

        @Tool(description = "Searches the Bedrock knowledge base with the AWS SDK")
        public String bedrock(String query) {
            return bedrock.retrieve(r -> r.knowledgeBaseId("KB1")
                    .retrievalQuery(q -> q.text(query))
                    .retrievalConfiguration(c -> c.vectorSearchConfiguration(v -> v.numberOfResults(5))))
                .retrievalResults()
                .stream()
                .map(result -> result.content().text())
                .collect(Collectors.joining("\n"));
        }

        @Tool(description = "Searches the knowledge base")
        public String kb(String query) {
            return knowledgeBase.similaritySearch(SearchRequest.builder().query(query).topK(4).build())
                .stream()
                .map(Document::getText)
                .collect(Collectors.joining("\n"));
        }

        @Tool(description = "Looks up the knowledge base")
        public String lookup(String query) {
            return vectorStore.similaritySearch(SearchRequest.builder().query(query).topK(2).build())
                .stream()
                .map(Document::getText)
                .collect(Collectors.joining("\n"));
        }

        @Tool(description = "Always fails")
        public String fail() {
            throw new IllegalStateException("tool broke");
        }

        @Tool(description = "Tool a")
        public String a() {
            return "a";
        }

        @Tool(description = "Tool b")
        public String b() {
            return "b";
        }
    }

    /** Like the Bedrock Knowledge Base store: emits no observation, random IDs, the source in metadata. */
    static final class FakeKnowledgeBase implements VectorStore {

        @Override
        public void add(List<Document> documents) {
        }

        @Override
        public void delete(List<String> idList) {
        }

        @Override
        public void delete(Filter.Expression filterExpression) {
        }

        @Override
        public List<Document> similaritySearch(SearchRequest request) {
            return List.of(
                    Document.builder().text("refunds take 5 days").metadata("source", "s3://kb/refunds.pdf").score(0.9).build(),
                    Document.builder().text("refunds need a receipt").metadata("source", "s3://kb/refunds.pdf").score(0.8).build());
        }
    }

    /** A Bedrock Agent Runtime client, as an application builds it: it emits no observation. */
    static final class FakeBedrockAgentRuntime implements BedrockAgentRuntimeClient {

        @Override
        public RetrieveResponse retrieve(RetrieveRequest request) {
            return RetrieveResponse.builder()
                .retrievalResults(result("invoices are monthly", "s3://kb/invoices.pdf", 0.7),
                        result("pay by card", "s3://kb/payments.pdf", 0.6))
                .build();
        }

        private static KnowledgeBaseRetrievalResult result(String text, String uri, double score) {
            return KnowledgeBaseRetrievalResult.builder()
                .content(c -> c.text(text))
                .location(l -> l.s3Location(s -> s.uri(uri)))
                .score(score)
                .build();
        }

        @Override
        public String serviceName() {
            return "bedrock-agent-runtime";
        }

        @Override
        public void close() {
        }
    }

    /** Embeds text as a small positive vector so cosine similarity is always defined. */
    static final class FakeEmbeddingModel implements EmbeddingModel {

        @Override
        public EmbeddingResponse call(EmbeddingRequest request) {
            List<Embedding> embeddings = new ArrayList<>();
            for (int i = 0; i < request.getInstructions().size(); i++) {
                embeddings.add(new Embedding(vector(request.getInstructions().get(i)), i));
            }
            return new EmbeddingResponse(embeddings);
        }

        @Override
        public float[] embed(Document document) {
            return vector(document.getText());
        }

        private static float[] vector(String text) {
            return new float[] { 1f, text.length() % 7 + 1, text.chars().sum() % 5 + 1 };
        }
    }

    /** Fails every save while {@link #FAIL_SAVES} is set. */
    static final class FlakyStore implements InsightsEventStore {

        private final InMemoryEventStore delegate = new InMemoryEventStore(1000);

        @Override
        public void save(ConversationTurn turn) {
            if (FAIL_SAVES.get()) {
                throw new IllegalStateException("store down");
            }
            delegate.save(turn);
        }

        @Override
        public Optional<ConversationTurn> findTurn(String turnId) {
            return delegate.findTurn(turnId);
        }

        @Override
        public List<ConversationTurn> findTurns(TurnQuery query) {
            return delegate.findTurns(query);
        }
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class App {

        @Bean
        ChatModel chatModel(ObservationRegistry registry) {
            return new ScriptedChatModel(registry, InsightsEndToEndTest::script);
        }

        @Bean
        SimpleVectorStore vectorStore(ObservationRegistry registry) {
            SimpleVectorStore store = SimpleVectorStore.builder(new FakeEmbeddingModel()).observationRegistry(registry).build();
            store.add(List.of(new Document("refunds take 5 days"), new Document("returns are free"),
                    new Document("shipping is 2 days")));
            return store;
        }

        @Bean
        VectorStore knowledgeBase() {
            return new FakeKnowledgeBase();
        }

        @Bean
        Tools tools(SimpleVectorStore vectorStore, @Qualifier("knowledgeBase") VectorStore knowledgeBase,
                BedrockAgentRuntimeClient bedrock) {
            return new Tools(vectorStore, knowledgeBase, bedrock);
        }

        @Bean
        BedrockAgentRuntimeClient bedrockAgentRuntimeClient() {
            return new FakeBedrockAgentRuntime();
        }

        @Bean
        InsightsEventStore flakyStore() {
            return new FlakyStore();
        }
    }
}
