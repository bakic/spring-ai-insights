package io.github.bakic.springaiinsights.bedrock;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import io.micrometer.observation.ObservationRegistry;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.observation.DefaultVectorStoreObservationConvention;
import org.springframework.ai.vectorstore.observation.VectorStoreObservationContext;
import org.springframework.ai.vectorstore.observation.VectorStoreObservationDocumentation;
import software.amazon.awssdk.services.bedrockagentruntime.BedrockAgentRuntimeClient;
import software.amazon.awssdk.services.bedrockagentruntime.model.Citation;
import software.amazon.awssdk.services.bedrockagentruntime.model.KnowledgeBaseRetrievalConfiguration;
import software.amazon.awssdk.services.bedrockagentruntime.model.KnowledgeBaseRetrievalResult;
import software.amazon.awssdk.services.bedrockagentruntime.model.KnowledgeBaseRetrieveAndGenerateConfiguration;
import software.amazon.awssdk.services.bedrockagentruntime.model.RetrievalResultContent;
import software.amazon.awssdk.services.bedrockagentruntime.model.RetrievalResultLocation;
import software.amazon.awssdk.services.bedrockagentruntime.model.RetrievedReference;
import software.amazon.awssdk.services.bedrockagentruntime.model.RetrieveAndGenerateConfiguration;
import software.amazon.awssdk.services.bedrockagentruntime.model.RetrieveAndGenerateRequest;
import software.amazon.awssdk.services.bedrockagentruntime.model.RetrieveAndGenerateResponse;
import software.amazon.awssdk.services.bedrockagentruntime.model.RetrieveAndGenerateType;
import software.amazon.awssdk.services.bedrockagentruntime.model.RetrieveRequest;
import software.amazon.awssdk.services.bedrockagentruntime.model.RetrieveResponse;

/**
 * Observes the knowledge base searches an application makes with the AWS SDK {@link BedrockAgentRuntimeClient}
 * directly, without a Spring AI vector store. Each {@code Retrieve} and knowledge base {@code RetrieveAndGenerate}
 * call emits Spring AI's vector-store query observation, with the store {@link BedrockKnowledgeBaseCatalog#STORE}, so
 * it is recorded on the current turn like any other retrieval and matched against the catalog's documents.
 * <p>
 * Each chunk's {@code source} metadata is its S3 URI or custom document ID (else its web, Confluence, SharePoint,
 * Salesforce or Kendra location). {@code Retrieve} returns the chunks with their scores. {@code RetrieveAndGenerate}
 * returns only the chunks cited in the answer, without scores, so it records each cited chunk once and no scores.
 * The number of results is recorded only when the request sets it. {@code RetrieveAndGenerate} on external sources is
 * not a knowledge base search and is not observed; neither are the paginators.
 */
public final class ObservedBedrockAgentRuntime {

    private static final Log logger = LogFactory.getLog(ObservedBedrockAgentRuntime.class);

    private static final DefaultVectorStoreObservationConvention CONVENTION = new DefaultVectorStoreObservationConvention();

    private ObservedBedrockAgentRuntime() {
    }

    /**
     * The client, observed: a proxy implementing {@link BedrockAgentRuntimeClient} that delegates every call to it.
     * A client already observed is returned as is.
     */
    public static BedrockAgentRuntimeClient wrap(BedrockAgentRuntimeClient client, ObservationRegistry registry) {
        if (isObserved(client)) {
            return client;
        }
        return (BedrockAgentRuntimeClient) Proxy.newProxyInstance(ObservedBedrockAgentRuntime.class.getClassLoader(),
                new Class<?>[] { BedrockAgentRuntimeClient.class, Observed.class }, new Handler(client, registry));
    }

    public static boolean isObserved(Object client) {
        return client instanceof Observed;
    }

    /** Marks observed clients, so a client is observed once. */
    interface Observed {

    }

    private record Handler(BedrockAgentRuntimeClient delegate, ObservationRegistry registry)
            implements InvocationHandler {

        @Override
        public @Nullable Object invoke(Object proxy, Method method, @Nullable Object[] args) throws Throwable {
            if (method.getDeclaringClass() == Object.class) {
                return switch (method.getName()) {
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    default -> "ObservedBedrockAgentRuntime[" + delegate + "]";
                };
            }
            if (args != null && args.length == 1) {
                if (method.getName().equals("retrieve")) {
                    return retrieve(retrieveRequest(args[0]));
                }
                if (method.getName().equals("retrieveAndGenerate")) {
                    return retrieveAndGenerate(retrieveAndGenerateRequest(args[0]));
                }
            }
            try {
                return method.invoke(delegate, args);
            }
            catch (InvocationTargetException e) {
                throw e.getCause();
            }
        }

        @SuppressWarnings("unchecked")
        private static RetrieveRequest retrieveRequest(Object argument) {
            return argument instanceof RetrieveRequest request ? request
                    : RetrieveRequest.builder().applyMutation((Consumer<RetrieveRequest.Builder>) argument).build();
        }

        @SuppressWarnings("unchecked")
        private static RetrieveAndGenerateRequest retrieveAndGenerateRequest(Object argument) {
            return argument instanceof RetrieveAndGenerateRequest request ? request
                    : RetrieveAndGenerateRequest.builder()
                        .applyMutation((Consumer<RetrieveAndGenerateRequest.Builder>) argument)
                        .build();
        }

        private RetrieveResponse retrieve(RetrieveRequest request) {
            String query = request.retrievalQuery() == null ? null : request.retrievalQuery().text();
            return observe(query, numberOfResults(request.retrievalConfiguration()), () -> delegate.retrieve(request),
                    response -> {
                        List<Document> documents = new ArrayList<>();
                        for (KnowledgeBaseRetrievalResult result : response.retrievalResults()) {
                            documents.add(document(result.content(), result.location(), result.score()));
                        }
                        return documents;
                    });
        }

        private RetrieveAndGenerateResponse retrieveAndGenerate(RetrieveAndGenerateRequest request) {
            RetrieveAndGenerateConfiguration configuration = request.retrieveAndGenerateConfiguration();
            if (configuration == null || configuration.type() != RetrieveAndGenerateType.KNOWLEDGE_BASE) {
                return delegate.retrieveAndGenerate(request);
            }
            KnowledgeBaseRetrieveAndGenerateConfiguration knowledgeBase = configuration.knowledgeBaseConfiguration();
            String query = request.input() == null ? null : request.input().text();
            return observe(query, knowledgeBase == null ? null : numberOfResults(knowledgeBase.retrievalConfiguration()),
                    () -> delegate.retrieveAndGenerate(request), response -> {
                        Map<List<@Nullable String>, Document> cited = new LinkedHashMap<>();
                        for (Citation citation : response.citations()) {
                            for (RetrievedReference reference : citation.retrievedReferences()) {
                                Document document = document(reference.content(), reference.location(), null);
                                cited.putIfAbsent(Arrays.asList(source(reference.location()),
                                        document.getText()), document);
                            }
                        }
                        return List.copyOf(cited.values());
                    });
        }

        private <R> R observe(@Nullable String query, @Nullable Integer numberOfResults, Supplier<R> call,
                Function<R, List<Document>> documents) {
            VectorStoreObservationContext.Builder builder = VectorStoreObservationContext
                .builder(BedrockKnowledgeBaseCatalog.STORE, VectorStoreObservationContext.Operation.QUERY);
            if (numberOfResults != null) {
                builder.queryRequest(SearchRequest.builder()
                    .query(query == null ? "" : query)
                    .topK(numberOfResults)
                    .build());
            }
            VectorStoreObservationContext context = builder.build();
            return VectorStoreObservationDocumentation.AI_VECTOR_STORE
                .observation(null, CONVENTION, () -> context, registry)
                .observe(() -> {
                    R response = call.get();
                    try {
                        context.setQueryResponse(documents.apply(response));
                    }
                    catch (RuntimeException e) {
                        logger.debug("Could not read the retrieved chunks of a Bedrock knowledge base response", e);
                    }
                    return response;
                });
        }

        private static @Nullable Integer numberOfResults(@Nullable KnowledgeBaseRetrievalConfiguration configuration) {
            return configuration == null || configuration.vectorSearchConfiguration() == null ? null
                    : configuration.vectorSearchConfiguration().numberOfResults();
        }

        private static Document document(@Nullable RetrievalResultContent content,
                @Nullable RetrievalResultLocation location, @Nullable Double score) {
            String text = content == null || content.text() == null ? "" : content.text();
            Document.Builder builder = Document.builder().text(text).score(score);
            String source = source(location);
            if (source != null) {
                builder.id(source).metadata("source", source);
            }
            return builder.build();
        }

        private static @Nullable String source(@Nullable RetrievalResultLocation location) {
            if (location == null) {
                return null;
            }
            if (location.s3Location() != null && location.s3Location().uri() != null) {
                return location.s3Location().uri();
            }
            if (location.customDocumentLocation() != null && location.customDocumentLocation().id() != null) {
                return location.customDocumentLocation().id();
            }
            if (location.webLocation() != null && location.webLocation().url() != null) {
                return location.webLocation().url();
            }
            if (location.confluenceLocation() != null && location.confluenceLocation().url() != null) {
                return location.confluenceLocation().url();
            }
            if (location.sharePointLocation() != null && location.sharePointLocation().url() != null) {
                return location.sharePointLocation().url();
            }
            if (location.salesforceLocation() != null && location.salesforceLocation().url() != null) {
                return location.salesforceLocation().url();
            }
            if (location.kendraDocumentLocation() != null) {
                return location.kendraDocumentLocation().uri();
            }
            return null;
        }
    }
}
