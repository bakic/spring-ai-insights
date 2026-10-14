package io.github.springaiinsights.mongodb;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Optional;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.ReplaceOptions;
import com.mongodb.client.model.Sorts;
import io.github.springaiinsights.analytics.Granularity;
import io.github.springaiinsights.analytics.InsightsAnalytics;
import io.github.springaiinsights.analytics.InsightsFilter;
import io.github.springaiinsights.analytics.ModelUsage;
import io.github.springaiinsights.analytics.RetrievalUsage;
import io.github.springaiinsights.analytics.TenantUsage;
import io.github.springaiinsights.analytics.TimeBucket;
import io.github.springaiinsights.analytics.ToolUsage;
import io.github.springaiinsights.analytics.UsageSummary;
import io.github.springaiinsights.analytics.UserUsage;
import io.github.springaiinsights.model.ConversationTurn;
import io.github.springaiinsights.store.InsightsEventStore;
import io.github.springaiinsights.store.TurnQuery;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.bson.Document;
import org.bson.conversions.Bson;

import org.springframework.data.mongodb.core.MongoOperations;

/**
 * Stores turns in the application's MongoDB (5.0 or later), one document per turn in {@code <prefix>turns}. Indexes,
 * including a TTL index that enforces the retention, are created on the first save rather than at startup, so an
 * unreachable MongoDB never prevents the application from starting.
 */
public final class MongoEventStore implements InsightsEventStore, InsightsAnalytics {

    private static final Log log = LogFactory.getLog(MongoEventStore.class);

    private static final long INDEX_RETRY_NANOS = Duration.ofMinutes(1).toNanos();

    private static final ReplaceOptions UPSERT = new ReplaceOptions().upsert(true);

    private final MongoOperations mongo;

    private final String collectionName;

    private final Duration retention;

    private volatile boolean indexesReady;

    private volatile long nextIndexAttempt = System.nanoTime();

    public MongoEventStore(MongoOperations mongo, String collectionPrefix, Duration retention) {
        if (retention.compareTo(Duration.ofMinutes(1)) < 0) {
            throw new IllegalArgumentException("retention must be at least 1 minute: " + retention);
        }
        this.mongo = mongo;
        this.collectionName = collectionPrefix + "turns";
        this.retention = retention;
    }

    public String collectionName() {
        return collectionName;
    }

    public Duration retention() {
        return retention;
    }

    /** Creates the indexes now; normally they are created by the first save. */
    public void ensureIndexes() {
        MongoIndexes.ensure(turns(), retention, mongo::executeCommand);
        indexesReady = true;
    }

    @Override
    public void save(ConversationTurn turn) {
        ensureIndexesIfDue();
        turns().replaceOne(Filters.eq("_id", turn.turnId()), TurnDocuments.toDocument(turn), UPSERT);
    }

    /**
     * An index failure does not lose the turn: the save goes ahead, and the indexes are retried a minute later. A
     * MongoDB that is down fails the save itself, which the writer counts.
     */
    private void ensureIndexesIfDue() {
        if (indexesReady || System.nanoTime() - nextIndexAttempt < 0) {
            return;
        }
        nextIndexAttempt = System.nanoTime() + INDEX_RETRY_NANOS;
        try {
            ensureIndexes();
        }
        catch (RuntimeException e) {
            log.warn("Could not create the indexes of MongoDB collection " + collectionName
                    + "; retrying in a minute. Without them old turns are not expired and queries are slow.", e);
        }
    }

    @Override
    public Optional<ConversationTurn> findTurn(String turnId) {
        Document doc = turns().find(Filters.eq("_id", turnId)).first();
        return Optional.ofNullable(doc).map(TurnDocuments::fromDocument);
    }

    @Override
    public List<ConversationTurn> findTurns(TurnQuery query) {
        List<Bson> filters = new ArrayList<>(List.of(Filters.eq("application", query.application()),
                Filters.gte("startedAt", Date.from(query.from())), Filters.lt("startedAt", Date.from(query.to()))));
        if (query.tenantId() != null) {
            filters.add(Filters.eq("tenantId", query.tenantId()));
        }
        return read(turns().find(Filters.and(filters)).sort(Sorts.descending("startedAt")).limit(query.limit()));
    }

    @Override
    public UsageSummary summary(InsightsFilter filter) {
        throw new UnsupportedOperationException();
    }

    @Override
    public List<TimeBucket> timeline(InsightsFilter filter, Granularity granularity) {
        throw new UnsupportedOperationException();
    }

    @Override
    public List<ModelUsage> models(InsightsFilter filter) {
        throw new UnsupportedOperationException();
    }

    @Override
    public List<ToolUsage> tools(InsightsFilter filter) {
        throw new UnsupportedOperationException();
    }

    @Override
    public List<RetrievalUsage> retrievals(InsightsFilter filter) {
        throw new UnsupportedOperationException();
    }

    @Override
    public List<UserUsage> topUsers(InsightsFilter filter, int limit) {
        throw new UnsupportedOperationException();
    }

    @Override
    public List<TenantUsage> tenants(InsightsFilter filter) {
        throw new UnsupportedOperationException();
    }

    @Override
    public List<ConversationTurn> conversation(String application, String conversationId) {
        throw new UnsupportedOperationException();
    }

    @Override
    public List<String> applications() {
        throw new UnsupportedOperationException();
    }

    private MongoCollection<Document> turns() {
        return mongo.getCollection(collectionName);
    }

    private static List<ConversationTurn> read(Iterable<Document> docs) {
        List<ConversationTurn> result = new ArrayList<>();
        for (Document doc : docs) {
            result.add(TurnDocuments.fromDocument(doc));
        }
        return result;
    }
}
