package io.github.springaiinsights.mongodb;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
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
            .append("emptyRetrievals", sum("$_emptyRetrievals"));
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
                number(t, "toolCalls"), number(t, "retrievals"), number(t, "emptyRetrievals"), 0);
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
                    (double) number(d, "durationMs") / number(d, "calls"), number(d, "repeatedCalls")))
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
        throw new UnsupportedOperationException("Plan 3 Task 3");
    }

    @Override
    public List<io.github.springaiinsights.analytics.DocumentUsage> documents(InsightsFilter filter, int limit) {
        throw new UnsupportedOperationException("Plan 3 Task 3");
    }

    @Override
    public io.github.springaiinsights.analytics.QualitySummary quality(InsightsFilter filter) {
        throw new UnsupportedOperationException("Plan 3 Task 3");
    }

    @Override
    public List<io.github.springaiinsights.feedback.Feedback> recentFeedback(InsightsFilter filter,
            io.github.springaiinsights.feedback.Rating rating, int limit) {
        throw new UnsupportedOperationException("Plan 3 Task 3");
    }

    @Override
    public List<io.github.springaiinsights.feedback.Feedback> conversationFeedback(String application,
            String conversationId) {
        throw new UnsupportedOperationException("Plan 3 Task 3");
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

    private static List<ConversationTurn> read(Iterable<Document> docs) {
        List<ConversationTurn> result = new ArrayList<>();
        for (Document doc : docs) {
            result.add(TurnDocuments.fromDocument(doc));
        }
        return result;
    }
}
