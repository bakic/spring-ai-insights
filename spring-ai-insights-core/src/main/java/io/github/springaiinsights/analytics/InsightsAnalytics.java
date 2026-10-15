package io.github.springaiinsights.analytics;

import java.util.List;

import io.github.springaiinsights.feedback.Feedback;
import io.github.springaiinsights.feedback.Rating;
import io.github.springaiinsights.model.ConversationTurn;
import org.jspecify.annotations.Nullable;

/**
 * Questions about recorded turns. Every store bundled with Spring AI Insights answers them identically. Costs are
 * never null: unpriced model calls contribute nothing and are counted separately. Turns without a user or tenant
 * are left out of per-user and per-tenant lists.
 */
public interface InsightsAnalytics {

    /** Maximum number of turns returned by {@link #conversation}. */
    int MAX_CONVERSATION_TURNS = 500;

    UsageSummary summary(InsightsFilter filter);

    /** Buckets that contain at least one turn, oldest first. */
    List<TimeBucket> timeline(InsightsFilter filter, Granularity granularity);

    /** Most called first, then by model name. */
    List<ModelUsage> models(InsightsFilter filter);

    /** Most called first, then by tool name. */
    List<ToolUsage> tools(InsightsFilter filter);

    /** Most queried first, then by store name. */
    List<RetrievalUsage> retrievals(InsightsFilter filter);

    /** Users with the most turns first, then by user ID; at most {@code limit}. */
    List<UserUsage> topUsers(InsightsFilter filter, int limit);

    /** Tenants with the most turns first, then by tenant ID. */
    List<TenantUsage> tenants(InsightsFilter filter);

    /** The turns of one conversation, oldest first, at most {@link #MAX_CONVERSATION_TURNS}. */
    List<ConversationTurn> conversation(String application, String conversationId);

    /**
     * Users active in the filter whose first turn ever, for the same application and tenant, is inside the range.
     */
    long newUsers(InsightsFilter filter);

    /** Documents returned most often first, then by store and document ID; at most {@code limit}. */
    List<DocumentUsage> documents(InsightsFilter filter, int limit);

    /** Feedback counts by creation time, finish reasons and error types of the selected turns. */
    QualitySummary quality(InsightsFilter filter);

    /** Feedback created in the range, newest first, then by turn ID; optionally only one rating. */
    List<Feedback> recentFeedback(InsightsFilter filter, @Nullable Rating rating, int limit);

    /** Feedback on the turns of one conversation, oldest first. */
    List<Feedback> conversationFeedback(String application, String conversationId);

    /** Names of the applications that recorded turns, sorted. */
    List<String> applications();
}
