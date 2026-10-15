package io.github.springaiinsights.web;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import io.github.springaiinsights.feedback.Feedback;
import io.github.springaiinsights.feedback.Rating;
import io.github.springaiinsights.knowledge.KnowledgeCorpusCatalog;
import io.github.springaiinsights.model.ConversationTurn;
import io.github.springaiinsights.model.ModelCall;
import io.github.springaiinsights.model.RetrievalRecord;
import io.github.springaiinsights.model.ToolCallRecord;
import io.github.springaiinsights.model.ToolOutcome;
import io.github.springaiinsights.model.TurnOutcome;
import io.github.springaiinsights.store.InMemoryEventStore;
import org.jspecify.annotations.Nullable;

/** Deterministic, realistic-looking data for {@link DashboardPreview}. */
final class PreviewData {

    private static final String[] TENANTS = { "acme", "globex", "initech", "umbrella" };

    private static final String[] TOOLS = { "searchOrders", "trackParcel", "refund", "updateAddress",
            "escalateToHuman" };

    /** Offered on every turn but never called, so the dashboard has a tool to flag. */
    private static final String NEVER_CALLED = "cancelSubscription";

    private static final double[] TOOL_WEIGHTS = { 0.45, 0.3, 0.12, 0.08, 0.05 };

    private static final String[] HELP_DOCS = { "refund-policy", "shipping-times", "return-label", "warranty",
            "payment-methods", "account-deletion", "gift-cards", "order-cancellation" };

    private static final String[] PRODUCT_DOCS = { "sku-1042", "sku-2210", "sku-3307", "sku-0815", "sku-7781" };

    private static final String[][] EXCHANGES = {
            { "Where is my order 4471?", "Your order 4471 left our warehouse yesterday and should arrive on Friday." },
            { "Can I get a refund for a damaged item?",
                    "Yes. I opened a refund request for the damaged item; you will receive it within 5 days." },
            { "How long does shipping to Lyon take?", "Standard shipping to Lyon takes 2 to 3 business days." },
            { "Change my delivery address please", "Done, the new address will be used for your next deliveries." },
            { "I want to talk to a human", "I am handing this conversation to a colleague, they will reply shortly." } };

    private static final String[] COMPLAINTS = { "The answer was wrong, my parcel is already here.",
            "It did not understand my question.", "<img src=x onerror=alert(1)> too slow", null,
            "Refund policy quoted is outdated." };

    private final Random random = new Random(7);

    private final List<String> users = new ArrayList<>();

    private PreviewData() {
        HexFormat hex = HexFormat.of();
        for (int i = 0; i < 45; i++) {
            byte[] bytes = new byte[8];
            this.random.nextBytes(bytes);
            this.users.add(hex.formatHex(bytes));
        }
    }

    /** Documents of the help center, some never retrieved, and a product catalog that is still loading. */
    static List<KnowledgeCorpusCatalog> catalogs() {
        Set<String> help = new HashSet<>(List.of(HELP_DOCS));
        help.addAll(List.of("holiday-hours-2023", "legacy-pricing", "loyalty-program", "store-locations",
                "privacy-request", "bulk-orders"));
        return List.of(catalog("help-center", Set.copyOf(help)), catalog("product-catalog", null));
    }

    private static KnowledgeCorpusCatalog catalog(String store, @Nullable Set<String> ids) {
        return new KnowledgeCorpusCatalog() {
            @Override
            public String store() {
                return store;
            }

            @Override
            public @Nullable Set<String> documentIds() {
                return ids;
            }
        };
    }

    static void seed(InMemoryEventStore store, Instant now) {
        PreviewData data = new PreviewData();
        data.application(store, "support-assistant", 1900, 30, now);
        data.application(store, "sales-copilot", 100, 30, now);
        data.application(store, "billing-bot", 3, 0, now.minus(Duration.ofDays(40)));
    }

    private void application(InMemoryEventStore store, String app, int turns, int days, Instant now) {
        int made = 0;
        int conversation = 0;
        while (made < turns) {
            conversation++;
            Instant start = businessHour(now, days);
            String userId = this.random.nextDouble() < 0.04 ? null : zipfUser();
            String tenant = userId == null ? null : TENANTS[Math.floorMod(userId.hashCode(), TENANTS.length)];
            String conversationId = "conv-" + app.charAt(0) + "-" + conversation;
            int length = 1 + this.random.nextInt(4);
            for (int i = 0; i < length && made < turns; i++, made++) {
                ConversationTurn turn = turn(app, conversationId, start, userId, tenant, i);
                store.save(turn);
                feedback(store, turn);
                start = start.plusSeconds(30 + this.random.nextInt(240));
            }
        }
    }

    private ConversationTurn turn(String app, String conversationId, Instant start, String userId, String tenant,
            int index) {
        double roll = this.random.nextDouble();
        TurnOutcome outcome = roll < 0.03 ? TurnOutcome.ERROR : roll < 0.045 ? TurnOutcome.CANCELLED : TurnOutcome.SUCCESS;
        List<ModelCall> calls = new ArrayList<>();
        List<ToolCallRecord> tools = new ArrayList<>();
        List<RetrievalRecord> retrievals = new ArrayList<>();
        int toolCount = this.random.nextDouble() < 0.55 ? 1 + this.random.nextInt(2) : 0;
        Map<String, Integer> repeats = new java.util.HashMap<>();
        for (int i = 0; i < toolCount; i++) {
            String name = weighted();
            int repeat = repeats.merge(name, 1, Integer::sum) - 1;
            boolean failed = this.random.nextDouble() < 0.04;
            tools.add(new ToolCallRecord(name, 40 + this.random.nextInt(900),
                    failed ? ToolOutcome.ERROR : ToolOutcome.SUCCESS, failed ? "HttpServerErrorException" : null,
                    repeat));
        }
        if (this.random.nextDouble() < 0.6) {
            boolean help = this.random.nextDouble() < 0.75;
            String[] docs = help ? HELP_DOCS : PRODUCT_DOCS;
            boolean none = this.random.nextDouble() < 0.08;
            int results = none ? 0 : 1 + this.random.nextInt(4);
            List<String> ids = new ArrayList<>();
            List<Double> scores = new ArrayList<>();
            double score = 0.92 - this.random.nextDouble() * 0.25;
            for (int i = 0; i < results; i++) {
                ids.add(docs[Math.min(docs.length - 1, (int) Math.abs(this.random.nextGaussian() * 2.2))]);
                scores.add(Math.round(score * 1000) / 1000.0);
                score -= 0.05;
            }
            retrievals.add(new RetrievalRecord(help ? "help-center" : "product-catalog", 4, results, scores,
                    ids.stream().distinct().toList(), 20 + this.random.nextInt(120)));
        }
        double modelRoll = this.random.nextDouble();
        String model = modelRoll < 0.7 ? "gpt-4.1-mini" : modelRoll < 0.93 ? "gpt-4.1" : "local-llama";
        for (int i = 0; i <= (toolCount > 0 ? 1 : 0); i++) {
            long in = 600 + this.random.nextInt(2400) + index * 400L;
            long out = 80 + this.random.nextInt(500);
            long cached = i > 0 ? in / 2 : 0;
            calls.add(new ModelCall("openai", model, in, out, cached, 0, 400 + this.random.nextInt(2600),
                    price(model, in, out), null));
        }
        BigDecimal total = calls.stream().map(ModelCall::cost).reduce(BigDecimal.ZERO,
                (a, b) -> a == null || b == null ? null : a.add(b));
        long latency = calls.stream().mapToLong(ModelCall::durationMs).sum()
                + tools.stream().mapToLong(ToolCallRecord::durationMs).sum();
        boolean streaming = this.random.nextBoolean();
        String finish = this.random.nextDouble() < 0.06 ? "LENGTH"
                : this.random.nextDouble() < 0.01 ? "CONTENT_FILTER" : "STOP";
        String[] exchange = EXCHANGES[this.random.nextInt(EXCHANGES.length)];
        boolean content = this.random.nextDouble() < 0.3;
        return new ConversationTurn(Long.toHexString(this.random.nextLong()), app, conversationId, false, start,
                userId, tenant, streaming, outcome, outcome == TurnOutcome.ERROR ? "TimeoutException" : null, latency,
                streaming ? 250L + this.random.nextInt(900) : null,
                outcome == TurnOutcome.SUCCESS ? List.of(finish) : List.of(), calls, tools, retrievals, total, "EUR",
                Map.of(), content ? exchange[0] : null,
                content && outcome == TurnOutcome.SUCCESS ? exchange[1] : null, offeredTools(),
                "%016x%016x".formatted(this.random.nextLong(), this.random.nextLong()));
    }

    private static List<String> offeredTools() {
        List<String> offered = new ArrayList<>(List.of(TOOLS));
        offered.add(NEVER_CALLED);
        return offered;
    }

    private void feedback(InMemoryEventStore store, ConversationTurn turn) {
        if (this.random.nextDouble() >= 0.12) {
            return;
        }
        boolean up = this.random.nextDouble() < 0.78;
        store.saveFeedback(new Feedback(turn.turnId(), turn.application(), turn.conversationId(), turn.userId(),
                turn.tenantId(), up ? Rating.UP : Rating.DOWN,
                up ? null : COMPLAINTS[this.random.nextInt(COMPLAINTS.length)],
                turn.startedAt().plusSeconds(60)));
    }

    private static BigDecimal price(String model, long in, long out) {
        BigDecimal[] perMillion = switch (model) {
            case "gpt-4.1-mini" -> new BigDecimal[] { new BigDecimal("0.40"), new BigDecimal("1.60") };
            case "gpt-4.1" -> new BigDecimal[] { new BigDecimal("2.00"), new BigDecimal("8.00") };
            default -> null;
        };
        if (perMillion == null) {
            return null;
        }
        return perMillion[0].multiply(BigDecimal.valueOf(in))
            .add(perMillion[1].multiply(BigDecimal.valueOf(out)))
            .divide(BigDecimal.valueOf(1_000_000), 8, RoundingMode.HALF_UP);
    }

    private String weighted() {
        double roll = this.random.nextDouble();
        for (int i = 0; i < TOOLS.length; i++) {
            roll -= TOOL_WEIGHTS[i];
            if (roll < 0) {
                return TOOLS[i];
            }
        }
        return TOOLS[0];
    }

    private String zipfUser() {
        int index = (int) Math.min(this.users.size() - 1, Math.floor(Math.pow(this.random.nextDouble(), 2.2) * this.users.size()));
        return this.users.get(index);
    }

    private Instant businessHour(Instant now, int days) {
        while (true) {
            Instant candidate = now.minusSeconds((long) (this.random.nextDouble() * Math.max(days, 1) * 86400));
            int hour = candidate.atZone(ZoneOffset.UTC).getHour();
            double weight = hour >= 7 && hour <= 18 ? 1 : 0.15;
            if (this.random.nextDouble() < weight && candidate.isBefore(now.minusSeconds(3600))) {
                return candidate;
            }
        }
    }

}
