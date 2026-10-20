package io.github.bakic.springaiinsights.analytics;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import io.github.bakic.springaiinsights.feedback.Feedback;
import io.github.bakic.springaiinsights.feedback.Rating;
import io.github.bakic.springaiinsights.model.ConversationTurn;
import org.jspecify.annotations.Nullable;

/**
 * Questions about recorded turns. Every store bundled with Spring AI Insights answers them identically. Costs are
 * never null: unpriced model calls contribute nothing and are counted separately. Turns without a user or tenant
 * are left out of per-user and per-tenant lists.
 */
public interface InsightsAnalytics {

    /** Maximum number of turns returned by {@link #conversation}: the latest ones. */
    int MAX_CONVERSATION_TURNS = 500;

    UsageSummary summary(InsightsFilter filter);

    /** Buckets that contain at least one turn, oldest first. */
    List<TimeBucket> timeline(InsightsFilter filter, Granularity granularity);

    /** Most called first, then by model name. */
    List<ModelUsage> models(InsightsFilter filter);

    /** Most called first, then by tool name. */
    List<ToolUsage> tools(InsightsFilter filter);

    /** Names of the tools offered to the model on the selected turns, distinct and sorted. */
    List<String> offeredTools(InsightsFilter filter);

    /** Most queried first, then by store name. */
    List<RetrievalUsage> retrievals(InsightsFilter filter);

    /** Users with the most turns first, then by user ID; at most {@code limit}. */
    List<UserUsage> topUsers(InsightsFilter filter, int limit);

    /** Tenants with the most turns first, then by tenant ID. */
    List<TenantUsage> tenants(InsightsFilter filter);

    /**
     * The turns of one conversation, oldest first. A conversation longer than {@link #MAX_CONVERSATION_TURNS} gives
     * its latest {@link #MAX_CONVERSATION_TURNS} turns; {@link #conversationTurns} tells how many it has in all.
     */
    List<ConversationTurn> conversation(String application, String conversationId);

    /**
     * How many turns one conversation has in all, which is more than the size of {@link #conversation} when that is
     * cut at {@link #MAX_CONVERSATION_TURNS}. The default cannot see past the cut; the bundled stores count them all.
     */
    default long conversationTurns(String application, String conversationId) {
        return conversation(application, conversationId).size();
    }

    /**
     * Users active in the filter whose first turn ever, for the same application and tenant, is inside the range.
     */
    long newUsers(InsightsFilter filter);

    /** Documents returned most often first, then by store and document ID; at most {@code limit}. */
    List<DocumentUsage> documents(InsightsFilter filter, int limit);

    /**
     * Documents returned least often first, then by store and document ID; at most {@code limit}. Only documents
     * returned at least once are known: compare {@link #retrievedDocumentIds} with the corpus for those never returned.
     */
    List<DocumentUsage> leastDocuments(InsightsFilter filter, int limit);

    /** IDs of the documents a store returned on the selected turns. */
    Set<String> retrievedDocumentIds(InsightsFilter filter, String store);

    /**
     * Conversations whose last turn failed, was cancelled, or was rated down. The last turn is taken over the turns of
     * the filter's application and tenant started from {@code filter.from()} on, with no upper bound, so a
     * conversation resumed after the range is not abandoned. That turn must start before both {@code filter.to()} and
     * {@code idleBefore}, so conversations still active are left out. An ERROR or CANCELLED turn is reported as such;
     * otherwise DOWN feedback on it gives NEGATIVE_FEEDBACK. {@code recent} is newest last turn first, then by
     * conversation ID, at most {@code limit}.
     */
    AbandonedConversations abandonedConversations(InsightsFilter filter, Instant idleBefore, int limit);

    /** Feedback counts by creation time, finish reasons and error types of the selected turns. */
    QualitySummary quality(InsightsFilter filter);

    /** Feedback created in the range, newest first, then by turn ID; optionally only one rating. */
    List<Feedback> recentFeedback(InsightsFilter filter, @Nullable Rating rating, int limit);

    /** Feedback on the turns of one conversation, oldest first. */
    List<Feedback> conversationFeedback(String application, String conversationId);

    /** Names of the applications that recorded turns, sorted. */
    List<String> applications();
}
