package io.github.springaiinsights.mongodb;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.ArrayList;
import java.util.Date;

import com.mongodb.ErrorCategory;
import com.mongodb.MongoWriteException;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import com.mongodb.ErrorCategory;
import com.mongodb.MongoWriteException;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.ReplaceOptions;
import com.mongodb.client.model.Sorts;
import io.github.springaiinsights.analytics.AbandonReason;
import io.github.springaiinsights.analytics.AbandonedConversation;
import io.github.springaiinsights.analytics.AbandonedConversations;
import io.github.springaiinsights.analytics.DocumentUsage;
import io.github.springaiinsights.analytics.Granularity;
import io.github.springaiinsights.analytics.InsightsAnalytics;
import io.github.springaiinsights.analytics.InsightsFilter;
import io.github.springaiinsights.analytics.KeyCount;
import io.github.springaiinsights.analytics.ModelUsage;
import io.github.springaiinsights.analytics.QualitySummary;
import io.github.springaiinsights.analytics.RetrievalUsage;
import io.github.springaiinsights.analytics.TenantUsage;
import io.github.springaiinsights.analytics.TimeBucket;
import io.github.springaiinsights.analytics.ToolUsage;
import io.github.springaiinsights.analytics.UsageSummary;
import io.github.springaiinsights.analytics.UserUsage;
import io.github.springaiinsights.feedback.Feedback;
import io.github.springaiinsights.feedback.FeedbackStore;
import io.github.springaiinsights.feedback.Rating;
import io.github.springaiinsights.model.ConversationTurn;
import io.github.springaiinsights.report.ReportLock;
import io.github.springaiinsights.store.InsightsEventStore;
import io.github.springaiinsights.store.TurnQuery;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.jspecify.annotations.Nullable;

import org.springframework.data.mongodb.core.MongoOperations;

/**
 * Stores turns in the application's MongoDB (5.0 or later), one document per turn in {@code <prefix>turns}, and
 * feedback in {@code <prefix>feedback}, one document per rated turn. Indexes,
 * including a TTL index that enforces the retention, are created on the first save rather than at startup, so an
 * unreachable MongoDB never prevents the application from starting.
 */
public final class MongoEventStore implements InsightsEventStore, FeedbackStore, InsightsAnalytics, ReportLock {

    private static final Log log = LogFactory.getLog(MongoEventStore.class);

    private static final long INDEX_RETRY_NANOS = Duration.ofMinutes(1).toNanos();

    private static final ReplaceOptions UPSERT = new ReplaceOptions().upsert(true);

    /** Per-turn totals used by several queries; unpriced model calls ($sum ignores nulls) add no cost. */
    private static final Document PER_TURN = new Document("$set", new Document()
        .append("_cost", new Document("$sum", "$modelCalls.cost"))
        .append("_in", new Document("$sum", "$modelCalls.inputTokens"))
        .append("_out", new Document("$sum", "$modelCalls.outputTokens"))
        .append("_cacheRead", new Document("$sum", "$modelCalls.cacheReadTokens"))
        .append("_cacheWrite", new Document("$sum", "$modelCalls.cacheWriteTokens"))
        .append("_unpriced", cond(new Document("$in", Arrays.asList(null, "$modelCalls.cost")), 1, 0))
        .append("_tools", new Document("$size", "$toolCalls"))
        .append("_retrievals", new Document("$size", "$retrievals"))
        .append("_emptyRetrievals", new Document("$size", new Document("$filter",
                new Document("input", "$retrievals").append("cond", eq("$$this.resultCount", 0))))));

    private final MongoOperations mongo;

    private final String collectionName;

    private final String feedbackCollectionName;

    private final String locksCollectionName;

    private final Duration retention;

    private volatile boolean indexesReady;

    private volatile long nextIndexAttempt = System.nanoTime();

    public MongoEventStore(MongoOperations mongo, String collectionPrefix, Duration retention) {
        if (retention.compareTo(Duration.ofMinutes(1)) < 0) {
            throw new IllegalArgumentException("retention must be at least 1 minute: " + retention);
        }
        this.mongo = mongo;
        this.collectionName = collectionPrefix + "turns";
        this.feedbackCollectionName = collectionPrefix + "feedback";
        this.locksCollectionName = collectionPrefix + "locks";
        this.retention = retention;
    }

    public String collectionName() {
        return collectionName;
    }

    public String feedbackCollectionName() {
        return feedbackCollectionName;
    }

    public String locksCollectionName() {
        return locksCollectionName;
    }

    public Duration retention() {
        return retention;
    }

    /** Creates the indexes now; normally they are created by the first save. */
    public void ensureIndexes() {
        MongoIndexes.ensure(turns(), feedback(), locks(), retention, mongo::executeCommand);
        indexesReady = true;
    }

    @Override
    public void save(ConversationTurn turn) {
        ensureIndexesIfDue();
        turns().replaceOne(Filters.eq("_id", turn.turnId()), TurnDocuments.toDocument(turn), UPSERT);
    }

    @Override
    public void saveFeedback(Feedback entry) {
        ensureIndexesIfDue();
        feedback().replaceOne(Filters.eq("_id", entry.turnId()), FeedbackDocuments.toDocument(entry), UPSERT);
    }

    /** Inserts the claim; a duplicate key means another instance claimed it first. */
    @Override
    public boolean claim(String key, Instant expiresAt) {
        ensureIndexesIfDue();
        try {
            locks().insertOne(new Document("_id", key).append("expiresAt", Date.from(expiresAt)));
            return true;
        }
        catch (MongoWriteException e) {
            if (e.getError().getCategory() == ErrorCategory.DUPLICATE_KEY) {
                return false;
            }
            throw e;
        }
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
            log.warn("Could not create the indexes of MongoDB collections " + collectionName + " and "
                    + feedbackCollectionName
                    + "; retrying in a minute. Without them old data is not expired and queries are slow.", e);
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
        Document totals = new Document("_id", null).append("turns", sum(1))
            .append("anonymousTurns", sum(cond(eq("$userId", null), 1, 0)))
            .append("errorTurns", sum(cond(eq("$outcome", "ERROR"), 1, 0)))
            .append("cancelledTurns", sum(cond(eq("$outcome", "CANCELLED"), 1, 0)))
            .append("inputTokens", sum("$_in"))
            .append("outputTokens", sum("$_out"))
            .append("cacheReadTokens", sum("$_cacheRead"))
            .append("cacheWriteTokens", sum("$_cacheWrite"))
            .append("cost", sum("$_cost"))
            .append("unpricedTurns", sum("$_unpriced"))
            .append("avgLatencyMs", new Document("$avg", "$latencyMs"))
            .append("avgTimeToFirstTokenMs", new Document("$avg", "$timeToFirstTokenMs"))
            .append("toolCalls", sum("$_tools"))
            .append("retrievals", sum("$_retrievals"))
            .append("emptyRetrievals", sum("$_emptyRetrievals"))
            .append("retrievalTurns", sum(cond(new Document("$gt", List.of("$_retrievals", 0)), 1, 0)));
        Document facets = new Document("totals", List.of(new Document("$group", totals)))
            .append("conversations", distinctCount("$conversationId"))
            .append("users", List.of(new Document("$match", new Document("userId", new Document("$ne", null))),
                    new Document("$group", new Document("_id", "$userId")), new Document("$count", "n")));
        Document result = aggregate(List.of(match(filter), PER_TURN, new Document("$facet", facets))).get(0);
        List<Document> totalsList = result.getList("totals", Document.class);
        if (totalsList.isEmpty()) {
            return new UsageSummary(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, BigDecimal.ZERO, 0, 0, null, 0, 0, 0, 0);
        }
        Document t = totalsList.get(0);
        Object ttft = t.get("avgTimeToFirstTokenMs");
        return new UsageSummary(number(t, "turns"), count(result, "conversations"), count(result, "users"),
                number(t, "anonymousTurns"), number(t, "errorTurns"), number(t, "cancelledTurns"),
                number(t, "inputTokens"), number(t, "outputTokens"), number(t, "cacheReadTokens"),
                number(t, "cacheWriteTokens"), cost(t.get("cost")), number(t, "unpricedTurns"),
                ((Number) t.get("avgLatencyMs")).doubleValue(), ttft == null ? null : ((Number) ttft).doubleValue(),
                number(t, "toolCalls"), number(t, "retrievals"), number(t, "emptyRetrievals"), number(t, "retrievalTurns"));
    }

    @Override
    public List<TimeBucket> timeline(InsightsFilter filter, Granularity granularity) {
        Document bucket = new Document("$dateTrunc", new Document("date", "$startedAt")
            .append("unit", granularity == Granularity.HOUR ? "hour" : "day")
            .append("timezone", timezone(filter.zone())));
        Document facets = new Document("totals",
                List.of(new Document("$group", new Document("_id", bucket).append("turns", sum(1))
                    .append("cost", sum("$_cost")))))
            .append("conversations", List.of(
                    new Document("$group", new Document("_id", new Document("b", bucket).append("c", "$conversationId"))),
                    new Document("$group", new Document("_id", "$_id.b").append("n", sum(1)))))
            .append("users", List.of(new Document("$match", new Document("userId", new Document("$ne", null))),
                    new Document("$group", new Document("_id", new Document("b", bucket).append("u", "$userId"))),
                    new Document("$group", new Document("_id", "$_id.b").append("n", sum(1)))));
        Document result = aggregate(List.of(match(filter), PER_TURN, new Document("$facet", facets))).get(0);
        Map<Date, Long> conversations = byId(result.getList("conversations", Document.class));
        Map<Date, Long> users = byId(result.getList("users", Document.class));
        return result.getList("totals", Document.class)
            .stream()
            .map(d -> {
                Date start = d.getDate("_id");
                return new TimeBucket(start.toInstant(), number(d, "turns"), conversations.getOrDefault(start, 0L),
                        users.getOrDefault(start, 0L), cost(d.get("cost")));
            })
            .sorted(Comparator.comparing(TimeBucket::start))
            .toList();
    }

    @Override
    public List<ModelUsage> models(InsightsFilter filter) {
        Document group = new Document("_id", "$modelCalls.model").append("calls", sum(1))
            .append("inputTokens", sum("$modelCalls.inputTokens"))
            .append("outputTokens", sum("$modelCalls.outputTokens"))
            .append("cacheReadTokens", sum("$modelCalls.cacheReadTokens"))
            .append("cacheWriteTokens", sum("$modelCalls.cacheWriteTokens"))
            .append("cost", sum("$modelCalls.cost"))
            .append("unpricedCalls", sum(cond(eq("$modelCalls.cost", null), 1, 0)));
        return aggregate(List.of(match(filter), new Document("$unwind", "$modelCalls"), new Document("$group", group),
                new Document("$sort", new Document("calls", -1).append("_id", 1))))
            .stream()
            .map(d -> new ModelUsage(d.getString("_id"), number(d, "calls"), number(d, "inputTokens"),
                    number(d, "outputTokens"), number(d, "cacheReadTokens"), number(d, "cacheWriteTokens"),
                    cost(d.get("cost")), number(d, "unpricedCalls")))
            .toList();
    }

    @Override
    public List<ToolUsage> tools(InsightsFilter filter) {
        Document perTurn = new Document("_id", new Document("tool", "$toolCalls.name").append("turn", "$_id"))
            .append("calls", sum(1))
            .append("errors", sum(cond(eq("$toolCalls.outcome", "ERROR"), 1, 0)))
            .append("durationMs", sum("$toolCalls.durationMs"))
            .append("repeatedCalls", sum(cond(new Document("$gt", List.of("$toolCalls.repeatIndex", 0)), 1, 0)));
        Document perTool = new Document("_id", "$_id.tool").append("calls", sum("$calls"))
            .append("errors", sum("$errors"))
            .append("turns", sum(1))
            .append("durationMs", sum("$durationMs"))
            .append("repeatedCalls", sum("$repeatedCalls"));
        return aggregate(List.of(match(filter), new Document("$unwind", "$toolCalls"), new Document("$group", perTurn),
                new Document("$group", perTool), new Document("$sort", new Document("calls", -1).append("_id", 1))))
            .stream()
            .map(d -> new ToolUsage(d.getString("_id"), number(d, "calls"), number(d, "errors"), number(d, "turns"),
                    (double) number(d, "durationMs") / number(d, "calls"),
                    p95(filter, d.getString("_id"), number(d, "calls")), number(d, "repeatedCalls")))
            .toList();
    }

    private long p95(InsightsFilter filter, String tool, long calls) {
        List<Document> ranked = aggregate(List.of(match(filter),
                new Document("$match", new Document("toolCalls.name", tool)), new Document("$unwind", "$toolCalls"),
                new Document("$match", new Document("toolCalls.name", tool)),
                new Document("$sort", new Document("toolCalls.durationMs", 1)),
                new Document("$skip", ToolUsage.p95Index(calls)), new Document("$limit", 1),
                new Document("$project", new Document("_id", 0).append("durationMs", "$toolCalls.durationMs"))));
        return ranked.isEmpty() ? 0 : number(ranked.get(0), "durationMs");
    }

    @Override
    public List<String> offeredTools(InsightsFilter filter) {
        return aggregate(List.of(match(filter), new Document("$unwind", "$offeredTools"),
                new Document("$group", new Document("_id", "$offeredTools")), new Document("$sort", new Document("_id", 1))))
            .stream()
            .map(d -> d.getString("_id"))
            .toList();
    }

    @Override
    public List<RetrievalUsage> retrievals(InsightsFilter filter) {
        Document group = new Document("_id", "$retrievals.store").append("queries", sum(1))
            .append("emptyQueries", sum(cond(eq("$retrievals.resultCount", 0), 1, 0)))
            .append("avgResultCount", new Document("$avg", "$retrievals.resultCount"))
            .append("avgTopScore", new Document("$avg", new Document("$max", "$retrievals.scores")));
        return aggregate(List.of(match(filter), new Document("$unwind", "$retrievals"), new Document("$group", group),
                new Document("$sort", new Document("queries", -1).append("_id", 1))))
            .stream()
            .map(d -> {
                Object topScore = d.get("avgTopScore");
                return new RetrievalUsage(d.getString("_id"), number(d, "queries"), number(d, "emptyQueries"),
                        ((Number) d.get("avgResultCount")).doubleValue(),
                        topScore == null ? null : ((Number) topScore).doubleValue());
            })
            .toList();
    }

    @Override
    public List<UserUsage> topUsers(InsightsFilter filter, int limit) {
        if (limit < 1) {
            return List.of();
        }
        Document perConversation = new Document("_id",
                new Document("user", "$userId").append("conversation", "$conversationId"))
            .append("turns", sum(1))
            .append("cost", sum("$_cost"))
            .append("lastSeen", new Document("$max", "$startedAt"));
        Document perUser = new Document("_id", "$_id.user").append("turns", sum("$turns"))
            .append("conversations", sum(1))
            .append("cost", sum("$cost"))
            .append("lastSeen", new Document("$max", "$lastSeen"));
        return aggregate(List.of(match(filter), new Document("$match", new Document("userId", new Document("$ne", null))),
                PER_TURN, new Document("$group", perConversation), new Document("$group", perUser),
                new Document("$sort", new Document("turns", -1).append("_id", 1)), new Document("$limit", limit)))
            .stream()
            .map(d -> new UserUsage(d.getString("_id"), number(d, "turns"), number(d, "conversations"),
                    cost(d.get("cost")), d.getDate("lastSeen").toInstant()))
            .toList();
    }

    @Override
    public List<TenantUsage> tenants(InsightsFilter filter) {
        Document perUser = new Document("_id", new Document("tenant", "$tenantId").append("user", "$userId"))
            .append("turns", sum(1))
            .append("cost", sum("$_cost"));
        Document perTenant = new Document("_id", "$_id.tenant").append("turns", sum("$turns"))
            .append("activeUsers", sum(cond(eq("$_id.user", null), 0, 1)))
            .append("cost", sum("$cost"));
        return aggregate(List.of(match(filter),
                new Document("$match", new Document("tenantId", new Document("$ne", null))), PER_TURN,
                new Document("$group", perUser), new Document("$group", perTenant),
                new Document("$sort", new Document("turns", -1).append("_id", 1))))
            .stream()
            .map(d -> new TenantUsage(d.getString("_id"), number(d, "turns"), number(d, "activeUsers"),
                    cost(d.get("cost"))))
            .toList();
    }

    @Override
    public List<ConversationTurn> conversation(String application, String conversationId) {
        return read(turns()
            .find(Filters.and(Filters.eq("application", application), Filters.eq("conversationId", conversationId)))
            .sort(Sorts.ascending("startedAt"))
            .limit(MAX_CONVERSATION_TURNS));
    }

    @Override
    public long newUsers(InsightsFilter filter) {
        Document match = new Document();
        if (filter.application() != null) {
            match.append("application", filter.application());
        }
        if (filter.tenantId() != null) {
            match.append("tenantId", filter.tenantId());
        }
        match.append("userId", new Document("$ne", null))
            .append("startedAt", new Document("$lt", Date.from(filter.to())));
        Date from = Date.from(filter.from());
        List<Document> counted = aggregate(List.of(new Document("$match", match),
                new Document("$group", new Document("_id", "$userId")
                    .append("first", new Document("$min", "$startedAt"))),
                new Document("$match", new Document("first", new Document("$gte", from))),
                new Document("$count", "n")));
        return counted.isEmpty() ? 0 : number(counted.get(0), "n");
    }

    @Override
    public List<DocumentUsage> documents(InsightsFilter filter, int limit) {
        if (limit < 1) {
            return List.of();
        }
        return aggregate(List.of(match(filter), new Document("$unwind", "$retrievals"),
                new Document("$unwind", "$retrievals.documentIds"),
                new Document("$group", new Document("_id",
                        new Document("store", "$retrievals.store").append("doc", "$retrievals.documentIds"))
                    .append("hits", sum(1))),
                new Document("$sort", new Document("hits", -1).append("_id.store", 1).append("_id.doc", 1)),
                new Document("$limit", limit)))
            .stream()
            .map(d -> {
                Document id = d.get("_id", Document.class);
                return new DocumentUsage(id.getString("store"), id.getString("doc"), number(d, "hits"));
            })
            .toList();
    }

    @Override
    public List<DocumentUsage> leastDocuments(InsightsFilter filter, int limit) {
        if (limit < 1) {
            return List.of();
        }
        return aggregate(List.of(match(filter), new Document("$unwind", "$retrievals"),
                new Document("$unwind", "$retrievals.documentIds"),
                new Document("$group", new Document("_id",
                        new Document("store", "$retrievals.store").append("doc", "$retrievals.documentIds"))
                    .append("hits", sum(1))),
                new Document("$sort", new Document("hits", 1).append("_id.store", 1).append("_id.doc", 1)),
                new Document("$limit", limit)))
            .stream()
            .map(d -> {
                Document id = d.get("_id", Document.class);
                return new DocumentUsage(id.getString("store"), id.getString("doc"), number(d, "hits"));
            })
            .toList();
    }

    @Override
    public Set<String> retrievedDocumentIds(InsightsFilter filter, String store) {
        return aggregate(List.of(match(filter), new Document("$unwind", "$retrievals"),
                new Document("$match", new Document("retrievals.store", store)),
                new Document("$unwind", "$retrievals.documentIds"),
                new Document("$group", new Document("_id", "$retrievals.documentIds"))))
            .stream()
            .map(d -> d.getString("_id"))
            .collect(Collectors.toUnmodifiableSet());
    }

    @Override
    public AbandonedConversations abandonedConversations(InsightsFilter filter, Instant idleBefore, int limit) {
        Instant cutoff = idleBefore.isBefore(filter.to()) ? idleBefore : filter.to();
        Document since = new Document();
        if (filter.application() != null) {
            since.append("application", filter.application());
        }
        since.append("startedAt", new Document("$gte", Date.from(filter.from())));
        if (filter.tenantId() != null) {
            since.append("tenantId", filter.tenantId());
        }
        Document last = new Document("_id", new Document("application", "$application")
            .append("conversationId", "$conversationId"))
            .append("lastTurnId", new Document("$first", "$_id"))
            .append("lastTurnAt", new Document("$first", "$startedAt"))
            .append("userId", new Document("$first", "$userId"))
            .append("outcome", new Document("$first", "$outcome"));
        Document abandoned = new Document("$or", List.of(new Document("outcome", new Document("$in", List.of("ERROR",
                "CANCELLED"))), new Document("feedback.rating", "DOWN")));
        Document facets = new Document("count", List.of(new Document("$count", "n"))).append("recent",
                List.of(new Document("$sort", new Document("lastTurnAt", -1).append("_id.conversationId", 1)),
                        new Document("$limit", Math.max(1, limit))));
        Document result = aggregate(List.of(new Document("$match", since),
                new Document("$sort", new Document("application", 1).append("conversationId", 1)
                    .append("startedAt", -1)
                    .append("_id", -1)),
                new Document("$group", last),
                new Document("$match", new Document("lastTurnAt", new Document("$lt", Date.from(cutoff)))),
                new Document("$lookup", new Document("from", feedbackCollectionName).append("localField", "lastTurnId")
                    .append("foreignField", "_id")
                    .append("as", "feedback")),
                new Document("$match", abandoned), new Document("$facet", facets)))
            .get(0);
        List<Document> count = result.getList("count", Document.class);
        List<AbandonedConversation> recent = limit < 1 ? List.of()
                : result.getList("recent", Document.class).stream().map(MongoEventStore::abandoned).toList();
        return new AbandonedConversations(count.isEmpty() ? 0 : number(count.get(0), "n"), recent);
    }

    private static AbandonedConversation abandoned(Document doc) {
        String outcome = doc.getString("outcome");
        AbandonReason reason = "ERROR".equals(outcome) ? AbandonReason.ERROR
                : "CANCELLED".equals(outcome) ? AbandonReason.CANCELLED : AbandonReason.NEGATIVE_FEEDBACK;
        return new AbandonedConversation(doc.get("_id", Document.class).getString("conversationId"),
                doc.getString("userId"), doc.getString("lastTurnId"), doc.getDate("lastTurnAt").toInstant(), reason);
    }

    @Override
    public QualitySummary quality(InsightsFilter filter) {
        Map<String, Long> ratings = new HashMap<>();
        feedback().aggregate(List.of(new Document("$match", feedbackMatch(filter)),
                new Document("$group", new Document("_id", "$rating").append("n", sum(1)))))
            .forEach(d -> ratings.put(d.getString("_id"), number(d, "n")));
        Document byCount = new Document("$sort", new Document("n", -1).append("_id", 1));
        Document facets = new Document("finishReasons", List.of(
                new Document("$project", new Document("r", new Document("$setUnion",
                        List.of(new Document("$ifNull", Arrays.asList("$finishReasons", List.of())), List.of())))),
                new Document("$unwind", "$r"), new Document("$group", new Document("_id", "$r").append("n", sum(1))),
                byCount))
            .append("errorTypes", List.of(new Document("$match", new Document("errorType", new Document("$ne", null))),
                    new Document("$group", new Document("_id", "$errorType").append("n", sum(1))), byCount));
        Document result = aggregate(List.of(match(filter), new Document("$facet", facets))).get(0);
        return new QualitySummary(ratings.getOrDefault(Rating.UP.name(), 0L),
                ratings.getOrDefault(Rating.DOWN.name(), 0L), keyCounts(result, "finishReasons"),
                keyCounts(result, "errorTypes"));
    }

    @Override
    public List<Feedback> recentFeedback(InsightsFilter filter, @Nullable Rating rating, int limit) {
        if (limit < 1) {
            return List.of();
        }
        Document match = feedbackMatch(filter);
        if (rating != null) {
            match.append("rating", rating.name());
        }
        return readFeedback(feedback().find(match)
            .sort(Sorts.orderBy(Sorts.descending("createdAt"), Sorts.ascending("_id")))
            .limit(limit));
    }

    @Override
    public List<Feedback> conversationFeedback(String application, String conversationId) {
        return readFeedback(feedback()
            .find(Filters.and(Filters.eq("application", application), Filters.eq("conversationId", conversationId)))
            .sort(Sorts.orderBy(Sorts.ascending("createdAt"), Sorts.ascending("_id"))));
    }

    @Override
    public List<String> applications() {
        return turns().distinct("application", String.class).into(new ArrayList<>()).stream().sorted().toList();
    }

    private static Document match(InsightsFilter filter) {
        Document match = new Document();
        if (filter.application() != null) {
            match.append("application", filter.application());
        }
        match.append("startedAt", new Document("$gte", Date.from(filter.from())).append("$lt", Date.from(filter.to())));
        if (filter.tenantId() != null) {
            match.append("tenantId", filter.tenantId());
        }
        return new Document("$match", match);
    }

    private static Document feedbackMatch(InsightsFilter filter) {
        Document match = new Document();
        if (filter.application() != null) {
            match.append("application", filter.application());
        }
        match.append("createdAt", new Document("$gte", Date.from(filter.from())).append("$lt", Date.from(filter.to())));
        if (filter.tenantId() != null) {
            match.append("tenantId", filter.tenantId());
        }
        return match;
    }

    private static List<KeyCount> keyCounts(Document facets, String name) {
        return facets.getList(name, Document.class)
            .stream()
            .map(d -> new KeyCount(d.getString("_id"), number(d, "n")))
            .toList();
    }

    private static List<Feedback> readFeedback(Iterable<Document> docs) {
        List<Feedback> result = new ArrayList<>();
        for (Document doc : docs) {
            result.add(FeedbackDocuments.fromDocument(doc));
        }
        return result;
    }

    private List<Document> aggregate(List<Document> pipeline) {
        return turns().aggregate(pipeline).allowDiskUse(true).into(new ArrayList<>());
    }

    /** MongoDB takes an Olson ID or an offset such as "+05:30"; Java names UTC "Z". */
    static String timezone(ZoneId zone) {
        ZoneId normalized = zone.normalized();
        if (normalized instanceof ZoneOffset offset) {
            return offset.getTotalSeconds() == 0 ? "+00:00" : offset.getId();
        }
        return normalized.getId();
    }

    private static List<Document> distinctCount(String field) {
        return List.of(new Document("$group", new Document("_id", field)), new Document("$count", "n"));
    }

    private static long count(Document facets, String name) {
        List<Document> counted = facets.getList(name, Document.class);
        return counted.isEmpty() ? 0 : number(counted.get(0), "n");
    }

    private static Map<Date, Long> byId(List<Document> docs) {
        Map<Date, Long> result = new HashMap<>();
        docs.forEach(d -> result.put(d.getDate("_id"), number(d, "n")));
        return result;
    }

    private static Document sum(Object expression) {
        return new Document("$sum", expression);
    }

    private static Document cond(Object condition, Object then, Object otherwise) {
        return new Document("$cond", Arrays.asList(condition, then, otherwise));
    }

    private static Document eq(String field, Object value) {
        return new Document("$eq", Arrays.asList(field, value));
    }

    private static long number(Document doc, String key) {
        Object value = doc.get(key);
        return value == null ? 0 : ((Number) value).longValue();
    }

    private static BigDecimal cost(Object value) {
        BigDecimal cost = TurnDocuments.bigDecimal(value);
        return cost == null ? BigDecimal.ZERO : cost;
    }

    private MongoCollection<Document> turns() {
        return mongo.getCollection(collectionName);
    }

    private MongoCollection<Document> feedback() {
        return mongo.getCollection(feedbackCollectionName);
    }

    private MongoCollection<Document> locks() {
        return mongo.getCollection(locksCollectionName);
    }

    private static List<ConversationTurn> read(Iterable<Document> docs) {
        List<ConversationTurn> result = new ArrayList<>();
        for (Document doc : docs) {
            result.add(TurnDocuments.fromDocument(doc));
        }
        return result;
    }
}
