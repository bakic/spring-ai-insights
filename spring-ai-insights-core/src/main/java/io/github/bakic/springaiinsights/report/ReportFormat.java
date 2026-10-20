package io.github.bakic.springaiinsights.report;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.UnaryOperator;

import io.github.bakic.springaiinsights.analytics.ToolUsage;
import io.github.bakic.springaiinsights.pricing.UnpricedModel;
import org.jspecify.annotations.Nullable;

/** Renders a {@link WeeklyReport} as Slack text, plain text or an HTML page. Data is escaped for each format. */
public final class ReportFormat {

    private static final String MINUS = "\u2212";

    private static final String DASH = "\u2013";

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH);

    private static final DateTimeFormatter DAY_YEAR = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);

    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH);

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    /** Beyond this, a change (typically against a near-zero week) is shown as a bound. */
    private static final BigDecimal MAX_PERCENT = BigDecimal.valueOf(9999);

    private ReportFormat() {
    }

    public static String subject(WeeklyReport report) {
        return title(report, UnaryOperator.identity());
    }

    public static String text(WeeklyReport report) {
        StringBuilder text = new StringBuilder(subject(report)).append('\n');
        for (Line line : lines(report, UnaryOperator.identity())) {
            text.append(line.label()).append(": ").append(line.value()).append(suffix(line.change())).append('\n');
        }
        String note = note(report);
        if (note != null) {
            text.append(note).append('\n');
        }
        for (String line : pricingLines(report, UnaryOperator.identity())) {
            text.append(line).append('\n');
        }
        if (report.dashboardUrl() != null) {
            text.append("Open the dashboard: ").append(report.dashboardUrl()).append('\n');
        }
        return text.toString();
    }

    public static String slack(WeeklyReport report) {
        StringBuilder text = new StringBuilder("*").append(title(report, ReportFormat::slackEscape)).append("*\n");
        for (Line line : lines(report, ReportFormat::slackEscape)) {
            text.append(line.label()).append(": ").append(line.value()).append(suffix(line.change())).append('\n');
        }
        String note = note(report);
        if (note != null) {
            text.append('_').append(note).append("_\n");
        }
        for (String line : pricingLines(report, ReportFormat::slackEscape)) {
            text.append(line).append('\n');
        }
        if (report.dashboardUrl() != null) {
            text.append('<').append(slackEscape(report.dashboardUrl().toString())).append("|Open the dashboard>\n");
        }
        return text.toString();
    }

    public static String html(WeeklyReport report) {
        String title = title(report, ReportFormat::htmlEscape);
        StringBuilder html = new StringBuilder("<!doctype html>\n<html lang=\"en\">\n<head>\n<meta charset=\"utf-8\">\n")
            .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n")
            .append("<title>").append(title).append("</title>\n</head>\n")
            .append("<body style=\"margin:0;padding:24px;background:#f6f7f9;color:#1f2328;")
            .append("font-family:-apple-system,'Segoe UI',Helvetica,Arial,sans-serif;font-size:14px\">\n")
            .append("<div style=\"max-width:640px;margin:0 auto;background:#ffffff;border:1px solid #d8dee4;")
            .append("border-radius:8px;padding:24px\">\n")
            .append("<h1 style=\"font-size:18px;margin:0 0 16px\">").append(title).append("</h1>\n")
            .append("<table style=\"width:100%;border-collapse:collapse\">\n");
        for (Line line : lines(report, ReportFormat::htmlEscape)) {
            html.append("<tr><th style=\"text-align:left;font-weight:600;padding:6px 12px 6px 0;")
                .append("border-top:1px solid #eaeef2;vertical-align:top\">")
                .append(line.label())
                .append("</th><td style=\"padding:6px 0;border-top:1px solid #eaeef2\">")
                .append(line.value());
            if (!line.change().isEmpty()) {
                html.append(" <span style=\"color:#59636e\">(").append(line.change()).append(")</span>");
            }
            html.append("</td></tr>\n");
        }
        html.append("</table>\n");
        String note = note(report);
        if (note != null) {
            html.append("<p style=\"color:#59636e;margin:16px 0 0\">").append(note).append("</p>\n");
        }
        List<String> pricing = pricingLines(report, ReportFormat::htmlEscape);
        if (!pricing.isEmpty()) {
            html.append("<p style=\"color:#b42318;margin:8px 0 0\">").append(pricing.get(0)).append("</p>\n")
                .append("<ul style=\"color:#59636e;margin:4px 0 0;padding-left:20px\">\n");
            for (String line : pricing.subList(1, pricing.size() - 1)) {
                html.append("<li>").append(line.substring(2)).append("</li>\n");
            }
            html.append("</ul>\n<p style=\"color:#59636e;margin:4px 0 0\">")
                .append(pricing.get(pricing.size() - 1))
                .append("</p>\n");
        }
        if (report.dashboardUrl() != null) {
            html.append("<p style=\"margin:16px 0 0\"><a style=\"color:#0969da\" href=\"")
                .append(htmlEscape(report.dashboardUrl().toString()))
                .append("\">Open the dashboard</a></p>\n");
        }
        return html.append("</div>\n</body>\n</html>\n").toString();
    }

    /**
     * The relative change from {@code previous} to {@code current}, as a signed whole percent: "new" when there was
     * nothing before, "no change" when equal, empty when {@code current} is unknown.
     */
    static String change(@Nullable Number current, @Nullable Number previous) {
        if (current == null) {
            return "";
        }
        BigDecimal now = decimal(current);
        if (previous == null || decimal(previous).signum() == 0) {
            return now.signum() == 0 ? "no change" : "new";
        }
        BigDecimal before = decimal(previous);
        BigDecimal percent = now.subtract(before).multiply(HUNDRED).divide(before.abs(), 0, RoundingMode.HALF_UP);
        if (percent.abs().compareTo(MAX_PERCENT) > 0) {
            return (percent.signum() > 0 ? ">" : "<") + signed(percent.signum() * MAX_PERCENT.intValue()) + "%";
        }
        return percent.signum() == 0 ? "no change" : signed(percent.intValue()) + "%";
    }

    /** The change between two rates in percentage points; empty when either is unknown. */
    static String pointsChange(@Nullable Double current, @Nullable Double previous) {
        if (current == null || previous == null) {
            return "";
        }
        int points = (int) Math.round((current - previous) * 100);
        if (points == 0) {
            return "no change";
        }
        return signed(points) + (Math.abs(points) == 1 ? " pt" : " pts");
    }

    private record Line(String label, String value, String change) {
    }

    private static List<Line> lines(WeeklyReport report, UnaryOperator<String> escape) {
        ReportFigures now = report.current();
        ReportFigures before = report.previous();
        String currency = escape.apply(report.currency());
        List<Line> lines = new ArrayList<>();
        String usersChange = change(now.activeUsers(), before.activeUsers());
        lines.add(new Line("Active users", Long.toString(now.activeUsers()),
                usersChange.isEmpty() ? "" : usersChange + " vs previous week"));
        lines.add(new Line("Conversations", Long.toString(now.conversations()),
                change(now.conversations(), before.conversations())));
        String cost = money(currency, now.cost()) + suffix(change(now.cost(), before.cost()));
        if (now.costPerConversation() != null) {
            cost += ", " + money(currency, now.costPerConversation()) + " per conversation"
                    + suffix(change(now.costPerConversation(), before.costPerConversation()));
        }
        lines.add(new Line("Cost", cost, ""));
        lines.add(new Line("Positive feedback", percent(now.positiveFeedbackRate()),
                pointsChange(now.positiveFeedbackRate(), before.positiveFeedbackRate())));
        lines.add(new Line("Knowledge base queries with no result", percent(now.emptyRetrievalRate()),
                pointsChange(now.emptyRetrievalRate(), before.emptyRetrievalRate())));
        lines.add(new Line("Top tools", tools(report.topTools(), escape), ""));
        MonthProjection projection = report.projection();
        lines.add(new Line("Projected cost for " + MONTH.format(projection.month()),
                money(currency, projection.projectedCost()) + " (" + money(currency, projection.costToDate())
                        + " so far)",
                ""));
        return lines;
    }

    private static String tools(List<ToolUsage> tools, UnaryOperator<String> escape) {
        if (tools.isEmpty()) {
            return "none";
        }
        List<String> names = new ArrayList<>();
        for (ToolUsage tool : tools) {
            names.add(escape.apply(tool.tool()) + " (" + tool.calls() + (tool.calls() == 1 ? " call)" : " calls)"));
        }
        return String.join(", ", names);
    }

    /**
     * Says which models have no price and why, once the report counts unpriced turns: prices match the exact model
     * name, so each name is shown with the configured entry it resembles, then the configured entries. The first
     * line is the instruction and the lines starting with "- " are the models.
     */
    private static List<String> pricingLines(WeeklyReport report, UnaryOperator<String> escape) {
        if (report.unpricedModels().isEmpty()) {
            return List.of();
        }
        List<String> lines = new ArrayList<>();
        lines.add("Prices match the exact model name the provider reports; update ai-insights.pricing.models "
                + "to price these models:");
        for (UnpricedModel model : report.unpricedModels()) {
            String cause = model.similar() == null ? "no similar name is configured"
                    : "configured " + escape.apply(model.similar()) + " is not the same name";
            lines.add("- " + escape.apply(model.model()) + " (" + model.calls() + (model.calls() == 1 ? " call" : " calls")
                    + "): " + cause);
        }
        lines.add(report.configuredModels().isEmpty() ? "No model is configured."
                : "Configured now: " + String.join(", ", report.configuredModels().stream().map(escape).toList()));
        return lines;
    }

    private static @Nullable String note(WeeklyReport report) {
        long unpriced = report.current().unpricedTurns();
        if (unpriced == 0) {
            return null;
        }
        return "Cost excludes " + unpriced + (unpriced == 1 ? " turn" : " turns") + " with unpriced models.";
    }

    private static String title(WeeklyReport report, UnaryOperator<String> escape) {
        LocalDate first = report.firstDay();
        LocalDate last = report.lastDay();
        String from = first.getYear() == last.getYear() ? DAY.format(first) : DAY_YEAR.format(first);
        return "AI assistant weekly report: " + escape.apply(report.application()) + ", " + from + " " + DASH + " "
                + DAY_YEAR.format(last);
    }

    private static String suffix(String change) {
        return change.isEmpty() ? "" : " (" + change + ")";
    }

    private static String money(String currency, BigDecimal amount) {
        return currency + " " + amount.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private static String percent(@Nullable Double rate) {
        return rate == null ? DASH : Math.round(rate * 100) + "%";
    }

    private static String signed(int value) {
        return value > 0 ? "+" + value : MINUS + Math.abs(value);
    }

    private static BigDecimal decimal(Number number) {
        return number instanceof BigDecimal decimal ? decimal : new BigDecimal(number.toString());
    }

    static String slackEscape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    static String htmlEscape(String text) {
        return text.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;");
    }
}
