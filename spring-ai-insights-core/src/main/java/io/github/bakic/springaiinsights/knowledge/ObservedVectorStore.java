package io.github.bakic.springaiinsights.knowledge;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.observation.DefaultVectorStoreObservationConvention;
import org.springframework.ai.vectorstore.observation.VectorStoreObservationContext;
import org.springframework.ai.vectorstore.observation.VectorStoreObservationDocumentation;

/**
 * Emits Spring AI's vector-store query observation for a store that emits none (for example the Bedrock Knowledge
 * Base store), so its queries reach Spring AI Insights, metrics and traces like those of any observed store. Writes
 * and deletes are delegated without observation.
 */
public final class ObservedVectorStore implements VectorStore {

    private static final DefaultVectorStoreObservationConvention CONVENTION = new DefaultVectorStoreObservationConvention();

    private final VectorStore delegate;

    private final String system;

    private final ObservationRegistry registry;

    public ObservedVectorStore(VectorStore delegate, String system, ObservationRegistry registry) {
        this.delegate = delegate;
        this.system = system;
        this.registry = registry;
    }

    /**
     * The database system recorded for a store: its simple class name without a trailing {@code VectorStore}, in
     * lower snake case ({@code BedrockKnowledgeBaseVectorStore} becomes {@code bedrock_knowledge_base}).
     */
    public static String systemName(VectorStore store) {
        String name = store.getClass().getSimpleName();
        if (name.endsWith("VectorStore")) {
            name = name.substring(0, name.length() - "VectorStore".length());
        }
        if (name.isEmpty()) {
            return "vector_store";
        }
        return name.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT);
    }

    public VectorStore delegate() {
        return delegate;
    }

    @Override
    public List<Document> similaritySearch(SearchRequest request) {
        VectorStoreObservationContext context = VectorStoreObservationContext
            .builder(system, VectorStoreObservationContext.Operation.QUERY)
            .queryRequest(request)
            .build();
        return VectorStoreObservationDocumentation.AI_VECTOR_STORE
            .observation(null, CONVENTION, () -> context, registry)
            .observe(() -> {
                List<Document> documents = delegate.similaritySearch(request);
                context.setQueryResponse(documents);
                return documents;
            });
    }

    @Override
    public String getName() {
        return delegate.getName();
    }

    @Override
    public void add(List<Document> documents) {
        delegate.add(documents);
    }

    @Override
    public void delete(List<String> idList) {
        delegate.delete(idList);
    }

    @Override
    public void delete(Filter.Expression filterExpression) {
        delegate.delete(filterExpression);
    }

    @Override
    public <T> Optional<T> getNativeClient() {
        return delegate.getNativeClient();
    }
}
