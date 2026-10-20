package io.github.bakic.springaiinsights.knowledge;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.observation.VectorStoreObservationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ObservedVectorStoreTest {

    private final ObservationRegistry registry = ObservationRegistry.create();

    private final List<Observation.Context> stopped = new ArrayList<>();

    ObservedVectorStoreTest() {
        registry.observationConfig().observationHandler(new ObservationHandler<>() {
            @Override
            public boolean supportsContext(Observation.Context context) {
                return true;
            }

            @Override
            public void onStop(Observation.Context context) {
                stopped.add(context);
            }
        });
    }

    @Test
    void queryIsObserved() {
        List<Document> documents = List.of(Document.builder().id("d1").text("a").build());
        SearchRequest request = SearchRequest.builder().query("q").topK(3).build();
        ObservedVectorStore store = new ObservedVectorStore(new FakeStore(documents), "fake", registry);

        assertThat(store.similaritySearch(request)).isEqualTo(documents);

        assertThat(stopped).singleElement().satisfies(stop -> {
            VectorStoreObservationContext context = (VectorStoreObservationContext) stop;
            assertThat(context.getOperationName()).isEqualTo("query");
            assertThat(context.getDatabaseSystem()).isEqualTo("fake");
            assertThat(context.getQueryRequest()).isSameAs(request);
            assertThat(context.getQueryResponse()).isEqualTo(documents);
            assertThat(context.getError()).isNull();
        });
    }

    @Test
    void failureIsObservedAndRethrown() {
        IllegalStateException failure = new IllegalStateException("down");
        FakeStore delegate = new FakeStore(List.of()) {
            @Override
            public List<Document> similaritySearch(SearchRequest request) {
                throw failure;
            }
        };
        ObservedVectorStore store = new ObservedVectorStore(delegate, "fake", registry);

        assertThatThrownBy(() -> store.similaritySearch(SearchRequest.builder().query("q").build())).isSameAs(failure);
        assertThat(stopped).singleElement().satisfies(context -> assertThat(context.getError()).isSameAs(failure));
    }

    @Test
    void writesAndNameDelegate() {
        FakeStore delegate = new FakeStore(List.of());
        ObservedVectorStore store = new ObservedVectorStore(delegate, "fake", registry);
        Filter.Expression expression = new Filter.Expression(Filter.ExpressionType.EQ, new Filter.Key("k"),
                new Filter.Value("v"));

        store.add(List.of(Document.builder().id("d1").text("a").build()));
        store.delete(List.of("d1"));
        store.delete(expression);

        assertThat(delegate.calls).containsExactly("add", "delete-ids", "delete-filter");
        assertThat(store.getName()).isEqualTo("FakeStore");
        assertThat(store.<String>getNativeClient()).contains("native");
        assertThat(store.delegate()).isSameAs(delegate);
        assertThat(stopped).isEmpty();
    }

    @Test
    void systemNames() {
        assertThat(ObservedVectorStore.systemName(new BedrockKnowledgeBaseVectorStore()))
            .isEqualTo("bedrock_knowledge_base");
        assertThat(ObservedVectorStore.systemName(new FakeKnowledgeBase())).isEqualTo("fake_knowledge_base");
        assertThat(ObservedVectorStore.systemName(new FakeStore(List.of()) {
        })).isEqualTo("vector_store");
    }

    static class FakeStore implements VectorStore {

        final List<String> calls = new ArrayList<>();

        private final List<Document> results;

        FakeStore(List<Document> results) {
            this.results = results;
        }

        @Override
        public String getName() {
            return "FakeStore";
        }

        @Override
        public void add(List<Document> documents) {
            calls.add("add");
        }

        @Override
        public void delete(List<String> idList) {
            calls.add("delete-ids");
        }

        @Override
        public void delete(Filter.Expression filterExpression) {
            calls.add("delete-filter");
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> Optional<T> getNativeClient() {
            return Optional.of((T) "native");
        }

        @Override
        public List<Document> similaritySearch(SearchRequest request) {
            return results;
        }
    }

    static final class BedrockKnowledgeBaseVectorStore extends FakeStore {

        BedrockKnowledgeBaseVectorStore() {
            super(List.of());
        }
    }

    static final class FakeKnowledgeBase extends FakeStore {

        FakeKnowledgeBase() {
            super(List.of());
        }
    }
}
