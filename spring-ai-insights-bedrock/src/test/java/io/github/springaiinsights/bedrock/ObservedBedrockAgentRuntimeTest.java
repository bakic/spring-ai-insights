package io.github.springaiinsights.bedrock;

import java.util.ArrayList;
import java.util.List;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.observation.VectorStoreObservationContext;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.bedrockagentruntime.BedrockAgentRuntimeClient;
import software.amazon.awssdk.services.bedrockagentruntime.model.Citation;
import software.amazon.awssdk.services.bedrockagentruntime.model.KnowledgeBaseRetrievalResult;
import software.amazon.awssdk.services.bedrockagentruntime.model.RetrievalResultContent;
import software.amazon.awssdk.services.bedrockagentruntime.model.RetrievalResultLocation;
import software.amazon.awssdk.services.bedrockagentruntime.model.RetrievedReference;
import software.amazon.awssdk.services.bedrockagentruntime.model.RetrieveAndGenerateRequest;
import software.amazon.awssdk.services.bedrockagentruntime.model.RetrieveAndGenerateResponse;
import software.amazon.awssdk.services.bedrockagentruntime.model.RetrieveAndGenerateType;
import software.amazon.awssdk.services.bedrockagentruntime.model.RetrieveRequest;
import software.amazon.awssdk.services.bedrockagentruntime.model.RetrieveResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

class ObservedBedrockAgentRuntimeTest {

    private final List<VectorStoreObservationContext> observed = new ArrayList<>();

    private final List<Throwable> errors = new ArrayList<>();

    private final ObservationRegistry registry = ObservationRegistry.create();

    private RetrieveResponse retrieveResponse = RetrieveResponse.builder().build();

    private RetrieveAndGenerateResponse generateResponse = RetrieveAndGenerateResponse.builder().build();

    private boolean failing;

    private boolean closed;

    private final BedrockAgentRuntimeClient delegate = new BedrockAgentRuntimeClient() {

        @Override
        public RetrieveResponse retrieve(RetrieveRequest request) {
            if (failing) {
                throw SdkClientException.create("no network");
            }
            return retrieveResponse;
        }

        @Override
        public RetrieveAndGenerateResponse retrieveAndGenerate(RetrieveAndGenerateRequest request) {
            return generateResponse;
        }

        @Override
        public String serviceName() {
            return "bedrock-agent-runtime";
        }

        @Override
        public void close() {
            closed = true;
        }
    };

    ObservedBedrockAgentRuntimeTest() {
        registry.observationConfig().observationHandler(new ObservationHandler<VectorStoreObservationContext>() {

            @Override
            public boolean supportsContext(Observation.Context context) {
                return context instanceof VectorStoreObservationContext;
            }

            @Override
            public void onError(VectorStoreObservationContext context) {
                errors.add(context.getError());
            }

            @Override
            public void onStop(VectorStoreObservationContext context) {
                observed.add(context);
            }
        });
    }

    private BedrockAgentRuntimeClient client() {
        return ObservedBedrockAgentRuntime.wrap(delegate, registry);
    }

    @Test
    void retrieveIsRecordedAsAKnowledgeBaseQueryWithSourcesAndScores() {
        retrieveResponse = RetrieveResponse.builder()
            .retrievalResults(result("s3://kb/a.pdf", "alpha", 0.82), result("s3://kb/b.pdf", "beta", 0.41))
            .build();

        RetrieveResponse response = client().retrieve(RetrieveRequest.builder()
            .knowledgeBaseId("KB1")
            .retrievalQuery(q -> q.text("how to pay"))
            .retrievalConfiguration(c -> c.vectorSearchConfiguration(v -> v.numberOfResults(7)))
            .build());

        assertThat(response).isSameAs(retrieveResponse);
        assertThat(observed).singleElement().satisfies(context -> {
            assertThat(context.getDatabaseSystem()).isEqualTo(BedrockKnowledgeBaseCatalog.STORE);
            assertThat(context.getOperationName()).isEqualTo(VectorStoreObservationContext.Operation.QUERY.value());
            assertThat(context.getQueryRequest().getTopK()).isEqualTo(7);
            assertThat(context.getQueryRequest().getQuery()).isEqualTo("how to pay");
            assertThat(context.getQueryResponse()).extracting(d -> d.getMetadata().get("source"))
                .containsExactly("s3://kb/a.pdf", "s3://kb/b.pdf");
            assertThat(context.getQueryResponse()).extracting(Document::getScore).containsExactly(0.82, 0.41);
            assertThat(context.getQueryResponse()).extracting(Document::getText).containsExactly("alpha", "beta");
        });
    }

    @Test
    void retrieveAndGenerateRecordsEachCitedChunkOnceWithoutInventingScores() {
        generateResponse = RetrieveAndGenerateResponse.builder()
            .citations(Citation.builder().retrievedReferences(reference("s3://kb/a.pdf", "alpha")).build(),
                    Citation.builder()
                        .retrievedReferences(reference("s3://kb/a.pdf", "alpha"), customReference("doc-42", "gamma"))
                        .build())
            .build();

        RetrieveAndGenerateResponse response = client().retrieveAndGenerate(knowledgeBaseRequest(3));

        assertThat(response).isSameAs(generateResponse);
        assertThat(observed).singleElement().satisfies(context -> {
            assertThat(context.getDatabaseSystem()).isEqualTo(BedrockKnowledgeBaseCatalog.STORE);
            assertThat(context.getQueryRequest().getTopK()).isEqualTo(3);
            assertThat(context.getQueryRequest().getQuery()).isEqualTo("how to pay");
            assertThat(context.getQueryResponse()).extracting(d -> d.getMetadata().get("source"))
                .containsExactly("s3://kb/a.pdf", "doc-42");
            assertThat(context.getQueryResponse()).extracting(Document::getScore).containsOnlyNulls();
        });
    }

    @Test
    void consumerOverloadsAreObservedToo() {
        client().retrieve(r -> r.knowledgeBaseId("KB1").retrievalQuery(q -> q.text("q")));
        client().retrieveAndGenerate(r -> r.input(i -> i.text("q"))
            .retrieveAndGenerateConfiguration(c -> c.type(RetrieveAndGenerateType.KNOWLEDGE_BASE)
                .knowledgeBaseConfiguration(k -> k.knowledgeBaseId("KB1").modelArn("arn"))));

        assertThat(observed).hasSize(2);
    }

    @Test
    void anUnknownNumberOfResultsIsRecordedAsUnknownNotAsADefault() {
        client().retrieve(r -> r.knowledgeBaseId("KB1").retrievalQuery(q -> q.text("q")));

        assertThat(observed).singleElement().satisfies(context -> assertThat(context.getQueryRequest()).isNull());
    }

    @Test
    void externalSourcesAreNotAKnowledgeBaseAndAreNotObserved() {
        client().retrieveAndGenerate(r -> r.input(i -> i.text("q"))
            .retrieveAndGenerateConfiguration(c -> c.type(RetrieveAndGenerateType.EXTERNAL_SOURCES)));

        assertThat(observed).isEmpty();
    }

    @Test
    void failuresPropagateAndAreRecordedOnTheObservation() {
        failing = true;

        assertThatExceptionOfType(SdkClientException.class)
            .isThrownBy(() -> client().retrieve(r -> r.knowledgeBaseId("KB1").retrievalQuery(q -> q.text("q"))));
        assertThat(errors).singleElement().isInstanceOf(SdkClientException.class);
        assertThat(observed).singleElement().satisfies(context -> assertThat(context.getQueryResponse()).isNull());
    }

    @Test
    void otherMethodsAreDelegated() {
        BedrockAgentRuntimeClient client = client();

        assertThat(client.serviceName()).isEqualTo("bedrock-agent-runtime");
        client.close();
        assertThat(closed).isTrue();
        assertThat(client.toString()).contains("Observed");
    }

    @Test
    void aClientIsWrappedOnce() {
        BedrockAgentRuntimeClient client = client();

        assertThat(ObservedBedrockAgentRuntime.wrap(client, registry)).isSameAs(client);
        assertThat(ObservedBedrockAgentRuntime.isObserved(client)).isTrue();
        assertThat(ObservedBedrockAgentRuntime.isObserved(delegate)).isFalse();
    }

    private static RetrieveAndGenerateRequest knowledgeBaseRequest(int numberOfResults) {
        return RetrieveAndGenerateRequest.builder()
            .input(i -> i.text("how to pay"))
            .retrieveAndGenerateConfiguration(c -> c.type(RetrieveAndGenerateType.KNOWLEDGE_BASE)
                .knowledgeBaseConfiguration(k -> k.knowledgeBaseId("KB1")
                    .modelArn("arn")
                    .retrievalConfiguration(r -> r.vectorSearchConfiguration(v -> v.numberOfResults(numberOfResults)))))
            .build();
    }

    private static KnowledgeBaseRetrievalResult result(String uri, String text, double score) {
        return KnowledgeBaseRetrievalResult.builder()
            .content(RetrievalResultContent.builder().text(text).build())
            .location(RetrievalResultLocation.builder().s3Location(s -> s.uri(uri)).build())
            .score(score)
            .build();
    }

    private static RetrievedReference reference(String uri, String text) {
        return RetrievedReference.builder()
            .content(RetrievalResultContent.builder().text(text).build())
            .location(RetrievalResultLocation.builder().s3Location(s -> s.uri(uri)).build())
            .build();
    }

    private static RetrievedReference customReference(String customId, String text) {
        return RetrievedReference.builder()
            .content(RetrievalResultContent.builder().text(text).build())
            .location(RetrievalResultLocation.builder().customDocumentLocation(c -> c.id(customId)).build())
            .build();
    }
}
