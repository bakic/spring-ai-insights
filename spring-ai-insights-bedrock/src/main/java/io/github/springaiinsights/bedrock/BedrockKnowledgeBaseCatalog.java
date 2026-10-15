package io.github.springaiinsights.bedrock;

import java.time.Duration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import io.github.springaiinsights.knowledge.KnowledgeCorpusCatalog;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.jspecify.annotations.Nullable;
import software.amazon.awssdk.services.bedrockagent.BedrockAgentClient;
import software.amazon.awssdk.services.bedrockagent.model.DataSourceSummary;
import software.amazon.awssdk.services.bedrockagent.model.DocumentIdentifier;
import software.amazon.awssdk.services.bedrockagent.model.DocumentStatus;
import software.amazon.awssdk.services.bedrockagent.model.KnowledgeBaseDocumentDetail;
import software.amazon.awssdk.services.bedrockagent.model.ListDataSourcesRequest;
import software.amazon.awssdk.services.bedrockagent.model.ListDataSourcesResponse;
import software.amazon.awssdk.services.bedrockagent.model.ListKnowledgeBaseDocumentsRequest;
import software.amazon.awssdk.services.bedrockagent.model.ListKnowledgeBaseDocumentsResponse;

/**
 * The documents of Amazon Bedrock Knowledge Bases, listed with the Bedrock Agent API and refreshed periodically. IDs
 * are the S3 URI or custom document ID, which the Spring AI Bedrock Knowledge Base store puts in the {@code source}
 * metadata of retrieved chunks. Only documents that can be retrieved (indexed, possibly partially) are listed. Needs
 * {@code bedrock:ListDataSources} and {@code bedrock:ListKnowledgeBaseDocuments}. Failures never propagate: a knowledge
 * base that cannot be listed keeps its previous documents (or none yet), and is retried at the next refresh.
 */
public final class BedrockKnowledgeBaseCatalog implements KnowledgeCorpusCatalog, AutoCloseable {

    /** The store name recorded for {@code BedrockKnowledgeBaseVectorStore} retrievals. */
    public static final String STORE = "bedrock_knowledge_base";

    private static final Log logger = LogFactory.getLog(BedrockKnowledgeBaseCatalog.class);

    private static final Set<DocumentStatus> RETRIEVABLE = Set.of(DocumentStatus.INDEXED,
            DocumentStatus.PARTIALLY_INDEXED, DocumentStatus.METADATA_PARTIALLY_INDEXED,
            DocumentStatus.METADATA_UPDATE_FAILED);

    private static final int PAGE_SIZE = 1000;

    private final Supplier<BedrockAgentClient> clientSupplier;

    private @Nullable BedrockAgentClient client;

    private final Map<String, Set<String>> loaded = new LinkedHashMap<>();

    private final List<String> knowledgeBaseIds;

    private final Duration refresh;

    private volatile @Nullable Set<String> documentIds;

    private @Nullable ScheduledExecutorService scheduler;

    public BedrockKnowledgeBaseCatalog(BedrockAgentClient client, List<String> knowledgeBaseIds, Duration refresh) {
        this(() -> client, knowledgeBaseIds, refresh);
    }

    /**
     * @param client creates the client at the first refresh; if it fails, it is called again at the next refresh
     */
    public BedrockKnowledgeBaseCatalog(Supplier<BedrockAgentClient> client, List<String> knowledgeBaseIds,
            Duration refresh) {
        if (knowledgeBaseIds.isEmpty()) {
            throw new IllegalArgumentException("at least one knowledge base ID is required");
        }
        if (refresh.compareTo(Duration.ofMinutes(1)) < 0) {
            throw new IllegalArgumentException("refresh must be at least 1 minute: " + refresh);
        }
        this.clientSupplier = client;
        this.knowledgeBaseIds = List.copyOf(knowledgeBaseIds);
        this.refresh = refresh;
    }

    @Override
    public String store() {
        return STORE;
    }

    @Override
    public @Nullable Set<String> documentIds() {
        return documentIds;
    }

    /**
     * Lists the documents now. A knowledge base that fails keeps its previous documents; until at least one knowledge
     * base has been listed, {@link #documentIds()} is {@code null}.
     */
    public synchronized void refresh() {
        BedrockAgentClient client;
        try {
            client = client();
        }
        catch (RuntimeException e) {
            logger.warn("Could not create the Bedrock Agent client, knowledge base documents not listed: " + e);
            logger.debug("Client creation failed", e);
            return;
        }
        for (String knowledgeBaseId : knowledgeBaseIds) {
            Set<String> ids = new HashSet<>();
            try {
                list(client, knowledgeBaseId, ids);
                loaded.put(knowledgeBaseId, ids);
            }
            catch (RuntimeException e) {
                logger.warn("Could not list the documents of knowledge base " + knowledgeBaseId + ": " + e);
                logger.debug("Listing failed", e);
            }
        }
        if (!loaded.isEmpty()) {
            Set<String> all = new HashSet<>();
            loaded.values().forEach(all::addAll);
            documentIds = Set.copyOf(all);
        }
    }

    private BedrockAgentClient client() {
        if (client == null) {
            client = clientSupplier.get();
        }
        return client;
    }

    private void refreshSafely() {
        try {
            refresh();
        }
        catch (Exception | LinkageError e) {
            logger.warn("Knowledge base documents refresh failed: " + e, e);
        }
    }

    /** Lists the documents now in the background, then every {@code refresh}. */
    public synchronized void start() {
        if (scheduler != null) {
            return;
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "ai-insights-bedrock-catalog");
            thread.setDaemon(true);
            return thread;
        });
        scheduler.scheduleWithFixedDelay(this::refreshSafely, 0, refresh.toMillis(), TimeUnit.MILLISECONDS);
    }

    @Override
    public synchronized void close() {
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
    }

    private void list(BedrockAgentClient client, String knowledgeBaseId, Set<String> ids) {
        String dataSourceToken = null;
        do {
            ListDataSourcesResponse dataSources = client.listDataSources(ListDataSourcesRequest.builder()
                .knowledgeBaseId(knowledgeBaseId)
                .nextToken(dataSourceToken)
                .build());
            for (DataSourceSummary dataSource : dataSources.dataSourceSummaries()) {
                listDocuments(client, knowledgeBaseId, dataSource.dataSourceId(), ids);
            }
            dataSourceToken = dataSources.nextToken();
        }
        while (dataSourceToken != null);
    }

    private void listDocuments(BedrockAgentClient client, String knowledgeBaseId, String dataSourceId, Set<String> ids) {
        String token = null;
        do {
            ListKnowledgeBaseDocumentsResponse page = client.listKnowledgeBaseDocuments(
                    ListKnowledgeBaseDocumentsRequest.builder()
                        .knowledgeBaseId(knowledgeBaseId)
                        .dataSourceId(dataSourceId)
                        .maxResults(PAGE_SIZE)
                        .nextToken(token)
                        .build());
            for (KnowledgeBaseDocumentDetail document : page.documentDetails()) {
                String id = RETRIEVABLE.contains(document.status()) ? id(document.identifier()) : null;
                if (id != null) {
                    ids.add(id);
                }
            }
            token = page.nextToken();
        }
        while (token != null);
    }

    private static @Nullable String id(@Nullable DocumentIdentifier identifier) {
        if (identifier == null) {
            return null;
        }
        if (identifier.s3() != null && identifier.s3().uri() != null) {
            return identifier.s3().uri();
        }
        return identifier.custom() == null ? null : identifier.custom().id();
    }
}
