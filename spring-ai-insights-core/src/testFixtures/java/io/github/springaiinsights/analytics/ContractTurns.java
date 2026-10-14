package io.github.springaiinsights.analytics;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import io.github.springaiinsights.model.ConversationTurn;
import io.github.springaiinsights.model.ModelCall;
import io.github.springaiinsights.model.RetrievalRecord;
import io.github.springaiinsights.model.ToolCallRecord;
import io.github.springaiinsights.model.ToolOutcome;
import io.github.springaiinsights.model.TurnOutcome;

/** The dataset of {@link InsightsAnalyticsContract}; expected values are worked out in the Plan 2 document. */
public final class ContractTurns {

    public static final ZoneId PARIS = ZoneId.of("Europe/Paris");

    public static final Instant FROM = Instant.parse("2026-09-30T22:00:00Z");

    public static final Instant TO = Instant.parse("2026-10-02T22:00:00Z");

    /** Application "shop", 1 and 2 October 2026 in Paris. */
    public static final InsightsFilter SHOP = new InsightsFilter("shop", FROM, TO, null, PARIS);

    public static final ConversationTurn T1 = turn("T1", "shop", "2026-10-01T08:00:00.123Z", "c1", "u1", "acme",
            TurnOutcome.SUCCESS, null, 1000, 200L, List.of(call("m1", 100, 50, 10, "0.0002")),
            List.of(new ToolCallRecord("lookup", 30, ToolOutcome.SUCCESS, null, 0),
                    new ToolCallRecord("lookup", 50, ToolOutcome.SUCCESS, null, 1)),
            List.of(new RetrievalRecord("kb", 4, 2, List.of(0.9, 0.5), List.of("d1", "d2"), 12)));

    public static final ConversationTurn T2 = turn("T2", "shop", "2026-10-01T09:00:00Z", "c1", "u1", "acme",
            TurnOutcome.SUCCESS, null, 3000, null, List.of(call("m1", 200, 100, 0, "0.0004")),
            List.of(new ToolCallRecord("refund", 10, ToolOutcome.ERROR, "IllegalStateException", 0)),
            List.of(new RetrievalRecord("kb", 4, 0, List.of(), List.of(), 8)));

    public static final ConversationTurn T3 = turn("T3", "shop", "2026-10-01T23:30:00Z", "c2", "u2", "globex",
            TurnOutcome.ERROR, "RuntimeException", 500, null, List.of(call("m2", 50, 0, 0, null)), List.of(),
            List.of());

    public static final ConversationTurn T4 = turn("T4", "shop", "2026-10-02T10:00:00Z", "c3", null, null,
            TurnOutcome.CANCELLED, null, 1500, 400L, List.of(call("m1", 10, 5, 0, "0.00002")), List.of(), List.of());

    public static final ConversationTurn T5 = turn("T5", "shop", "2026-10-02T11:00:00Z", "c4", "u3", "acme",
            TurnOutcome.SUCCESS, null, 2000, null,
            List.of(call("m1", 300, 200, 0, "0.0007"), call("m2", 20, 10, 0, null)), List.of(), List.of());

    public static final ConversationTurn T6 = turn("T6", "crm", "2026-10-01T12:00:00Z", "c9", "u1", "acme",
            TurnOutcome.SUCCESS, null, 100, null, List.of(call("m1", 1, 1, 0, "0.000003")), List.of(), List.of());

    public static final ConversationTurn T7 = turn("T7", "shop", "2026-09-30T21:59:59.999Z", "c6", "u1", "acme",
            TurnOutcome.SUCCESS, null, 100, null, List.of(call("m1", 1, 1, 0, "0.000003")), List.of(), List.of());

    public static final ConversationTurn T8 = turn("T8", "shop", "2026-09-30T22:00:00Z", "c5", "u2", "globex",
            TurnOutcome.SUCCESS, null, 1000, null, List.of(call("m1", 0, 0, 0, "0")), List.of(), List.of());

    private ContractTurns() {
    }

    public static List<ConversationTurn> all() {
        return List.of(T1, T2, T3, T4, T5, T6, T7, T8);
    }

    private static ModelCall call(String model, long in, long out, long cacheRead, String cost) {
        return new ModelCall("bedrock", model, in, out, cacheRead, 0, 100, cost == null ? null : new BigDecimal(cost),
                null);
    }

    private static ConversationTurn turn(String id, String app, String startedAt, String conversation, String user,
            String tenant, TurnOutcome outcome, String errorType, long latency, Long ttft, List<ModelCall> calls,
            List<ToolCallRecord> tools, List<RetrievalRecord> retrievals) {
        BigDecimal total = BigDecimal.ZERO;
        for (ModelCall call : calls) {
            total = call.cost() == null || total == null ? null : total.add(call.cost());
        }
        return new ConversationTurn(id, app, conversation, false, Instant.parse(startedAt), user, tenant,
                id.equals("T1"), outcome, errorType, latency, ttft, List.of("STOP"), calls, tools, retrievals, total,
                "USD", id.equals("T1") ? Map.of("channel", "web") : Map.of(), null, null);
    }
}
