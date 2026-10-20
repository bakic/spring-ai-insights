package io.github.bakic.springaiinsights.demo;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import io.micrometer.observation.ObservationRegistry;

import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/** The help-center articles in {@code classpath:help-center/}, one document per article, ID = file name. */
@Configuration(value = "helpCenterConfiguration", proxyBeanMethods = false)
class HelpCenter {

    static final int TOP_K = 3;

    static final double SIMILARITY_THRESHOLD = 0.35;

    @Bean
    SimpleVectorStore helpCenter(ObservationRegistry registry) {
        SimpleVectorStore store = SimpleVectorStore.builder(new HashingEmbeddingModel()).observationRegistry(registry).build();
        store.add(articles());
        return store;
    }

    static List<Document> articles() {
        try {
            Resource[] resources = new PathMatchingResourcePatternResolver().getResources("classpath:help-center/*.md");
            List<Document> documents = new ArrayList<>();
            for (Resource resource : resources) {
                documents.add(Document.builder()
                    .id(resource.getFilename())
                    .text(resource.getContentAsString(StandardCharsets.UTF_8).strip())
                    .metadata("source", "help-center/" + resource.getFilename())
                    .build());
            }
            documents.sort(Comparator.comparing(Document::getId));
            return documents;
        }
        catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
