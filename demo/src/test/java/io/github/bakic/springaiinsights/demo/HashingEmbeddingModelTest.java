package io.github.bakic.springaiinsights.demo;

import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;

import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.SimpleVectorStore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class HashingEmbeddingModelTest {

    private final HashingEmbeddingModel model = new HashingEmbeddingModel();

    @Test
    void sameTextSameVector() {
        assertThat(model.embed("Where is my refund?")).isEqualTo(model.embed("Where is my refund?"));
        assertThat(model.dimensions()).isEqualTo(256);
    }

    @Test
    void vectorsAreUnitLength() {
        float[] vector = model.embed("Reset my password please");

        double norm = 0;
        for (float v : vector) {
            norm += v * v;
        }
        assertThat(vector).hasSize(256);
        assertThat(Math.sqrt(norm)).isCloseTo(1.0, within(1e-5));
    }

    @Test
    void refundQuestionIsClosestToRefundArticle() {
        assertThat(search("how do I get my money back for a refund")).first()
            .extracting(Document::getId)
            .isEqualTo("refunds.md");
    }

    @Test
    void offTopicQuestionRetrievesNothing() {
        assertThat(search("what is the capital of Peru")).isEmpty();
    }

    private java.util.List<Document> search(String query) {
        SimpleVectorStore store = new HelpCenter().helpCenter(ObservationRegistry.NOOP);
        return store.similaritySearch(SearchRequest.builder()
            .query(query)
            .topK(HelpCenter.TOP_K)
            .similarityThreshold(HelpCenter.SIMILARITY_THRESHOLD)
            .build());
    }
}
