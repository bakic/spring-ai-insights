package io.github.bakic.springaiinsights.bedrock;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.bedrockagent.BedrockAgentClient;
import software.amazon.awssdk.services.bedrockagent.model.DataSourceSummary;
import software.amazon.awssdk.services.bedrockagent.model.DocumentIdentifier;
import software.amazon.awssdk.services.bedrockagent.model.DocumentStatus;
import software.amazon.awssdk.services.bedrockagent.model.KnowledgeBaseDocumentDetail;
import software.amazon.awssdk.services.bedrockagent.model.ListDataSourcesRequest;
import software.amazon.awssdk.services.bedrockagent.model.ListDataSourcesResponse;
import software.amazon.awssdk.services.bedrockagent.model.ListKnowledgeBaseDocumentsRequest;
import software.amazon.awssdk.services.bedrockagent.model.ListKnowledgeBaseDocumentsResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class BedrockKnowledgeBaseCatalogTest {

    private final AtomicBoolean failing = new AtomicBoolean();

    private final List<ListKnowledgeBaseDocumentsRequest> documentRequests = new ArrayList<>();

    private List<KnowledgeBaseDocumentDetail> ds2Documents = List.of(document("ds2", "s3://kb/b.pdf", DocumentStatus.PARTIALLY_INDEXED));

    private final BedrockAgentClient client = new BedrockAgentClient() {

        @Override
        public ListDataSourcesResponse listDataSources(ListDataSourcesRequest request) {
            if (failing.get() || request.knowledgeBaseId().equals("BAD")) {
                throw SdkClientException.create("no network");
            }
            assertThat(request.knowledgeBaseId()).isEqualTo("KB1");
            return request.nextToken() == null
                    ? ListDataSourcesResponse.builder().dataSourceSummaries(dataSource("ds1")).nextToken("p2").build()
                    : ListDataSourcesResponse.builder().dataSourceSummaries(dataSource("ds2")).build();
        }

        @Override
        public ListKnowledgeBaseDocumentsResponse listKnowledgeBaseDocuments(ListKnowledgeBaseDocumentsRequest request) {
            documentRequests.add(request);
            if (request.dataSourceId().equals("ds2")) {
                return ListKnowledgeBaseDocumentsResponse.builder().documentDetails(ds2Documents).build();
            }
            if (request.nextToken() == null) {
                return ListKnowledgeBaseDocumentsResponse.builder()
                    .documentDetails(document("ds1", "s3://kb/a.pdf", DocumentStatus.INDEXED),
                            document("ds1", "s3://kb/failed.pdf", DocumentStatus.FAILED),
                            document("ds1", "s3://kb/deleting.pdf", DocumentStatus.DELETING))
                    .nextToken("d2")
                    .build();
            }
            return ListKnowledgeBaseDocumentsResponse.builder()
                .documentDetails(KnowledgeBaseDocumentDetail.builder()
                    .knowledgeBaseId("KB1")
                    .dataSourceId("ds1")
                    .status(DocumentStatus.METADATA_PARTIALLY_INDEXED)
                    .identifier(DocumentIdentifier.builder().custom(c -> c.id("doc-42")).build())
                    .build(), document("ds1", "s3://kb/pending.pdf", DocumentStatus.PENDING))
                .build();
        }

        @Override
        public String serviceName() {
            return "bedrock";
        }

        @Override
        public void close() {
        }
    };

    private static DataSourceSummary dataSource(String id) {
        return DataSourceSummary.builder().knowledgeBaseId("KB1").dataSourceId(id).build();
    }

    private static KnowledgeBaseDocumentDetail document(String dataSource, String uri, DocumentStatus status) {
        return KnowledgeBaseDocumentDetail.builder()
            .knowledgeBaseId("KB1")
            .dataSourceId(dataSource)
            .status(status)
            .identifier(DocumentIdentifier.builder().s3(s -> s.uri(uri)).build())
            .build();
    }

    private BedrockKnowledgeBaseCatalog catalog() {
        return new BedrockKnowledgeBaseCatalog(client, List.of("KB1"), Duration.ofHours(1));
    }

    @Test
    void listsAllPagesOfAllDataSources() {
        var catalog = catalog();
        assertThat(catalog.store()).isEqualTo("bedrock_knowledge_base");
        assertThat(catalog.documentIds()).isNull();
        catalog.refresh();
        assertThat(catalog.documentIds()).containsExactlyInAnyOrder("s3://kb/a.pdf", "s3://kb/b.pdf", "doc-42");
        assertThat(documentRequests).allSatisfy(r -> {
            assertThat(r.knowledgeBaseId()).isEqualTo("KB1");
            assertThat(r.maxResults()).isEqualTo(1000);
        });
    }

    @Test
    void oneFailingKnowledgeBaseDoesNotHideTheOthers() {
        var catalog = new BedrockKnowledgeBaseCatalog(client, List.of("BAD", "KB1"), Duration.ofHours(1));
        catalog.refresh();
        assertThat(catalog.documentIds()).containsExactlyInAnyOrder("s3://kb/a.pdf", "s3://kb/b.pdf", "doc-42");
    }

    @Test
    void clientCreationFailureIsRetriedAtTheNextRefresh() {
        AtomicBoolean created = new AtomicBoolean();
        var catalog = new BedrockKnowledgeBaseCatalog(() -> {
            if (!created.getAndSet(true)) {
                throw SdkClientException.create("Unable to load region");
            }
            return client;
        }, List.of("KB1"), Duration.ofHours(1));
        catalog.refresh();
        assertThat(catalog.documentIds()).isNull();
        catalog.refresh();
        assertThat(catalog.documentIds()).hasSize(3);
    }

    @Test
    void skipsDocumentsNotRetrievable() {
        var catalog = catalog();
        catalog.refresh();
        assertThat(catalog.documentIds()).doesNotContain("s3://kb/failed.pdf", "s3://kb/deleting.pdf",
                "s3://kb/pending.pdf");
    }

    @Test
    void firstLoadFailureLeavesCatalogUnloaded() {
        failing.set(true);
        var catalog = catalog();
        catalog.refresh();
        assertThat(catalog.documentIds()).isNull();
    }

    @Test
    void failedRefreshKeepsPreviousSnapshot() {
        var catalog = catalog();
        catalog.refresh();
        failing.set(true);
        catalog.refresh();
        assertThat(catalog.documentIds()).containsExactlyInAnyOrder("s3://kb/a.pdf", "s3://kb/b.pdf", "doc-42");
    }

    @Test
    void refreshReplacesSnapshot() {
        var catalog = catalog();
        catalog.refresh();
        ds2Documents = List.of();
        catalog.refresh();
        assertThat(catalog.documentIds()).containsExactlyInAnyOrder("s3://kb/a.pdf", "doc-42");
    }

    @Test
    void startLoadsInBackground() throws Exception {
        try (var catalog = catalog()) {
            catalog.start();
            long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while (catalog.documentIds() == null && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            assertThat(catalog.documentIds()).hasSize(3);
        }
    }

    @Test
    void invalidArgumentsAreRejected() {
        assertThatIllegalArgumentException()
            .isThrownBy(() -> new BedrockKnowledgeBaseCatalog(client, List.of(), Duration.ofHours(1)));
        assertThatIllegalArgumentException()
            .isThrownBy(() -> new BedrockKnowledgeBaseCatalog(client, List.of("KB1"), Duration.ofSeconds(59)));
    }
}
