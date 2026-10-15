package io.github.springaiinsights.report;

import java.math.BigDecimal;
import java.net.URI;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import io.github.springaiinsights.analytics.ToolUsage;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ReportFormatTest {

    private static final ReportFigures CURRENT = new ReportFigures(42, 310, 600, new BigDecimal("128.40"),
            new BigDecimal("0.4142"), 0.82, 0.07, 3);

    private static final ReportFigures PREVIOUS = new ReportFigures(40, 320, 640, new BigDecimal("114.64"),
            new BigDecimal("0.3600"), 0.79, 0.08, 0);

    private static final ReportFigures EMPTY = new ReportFigures(0, 0, 0, BigDecimal.ZERO, null, null, null, 0);

    private static final MonthProjection PROJECTION = new MonthProjection(YearMonth.of(2026, 10),
            new BigDecimal("300.10"), 18, new BigDecimal("540"));

    private static WeeklyReport report(String application, List<ToolUsage> tools, ReportFigures current,
            ReportFigures previous, URI dashboard) {
        return new WeeklyReport(application, "USD", LocalDate.parse("2026-10-12"), LocalDate.parse("2026-10-18"),
                current, previous, tools, PROJECTION, dashboard);
    }

    private static final WeeklyReport REPORT = report("helpdesk",
            List.of(tool("search_orders", 520), tool("create_ticket", 210)), CURRENT, PREVIOUS,
            URI.create("https://example.com/ai-insights/"));

    private static ToolUsage tool(String name, long calls) {
        return new ToolUsage(name, calls, 0, calls, 1, 1, 0);
    }

    @Test
    void textHasEveryFigure() {
        assertThat(ReportFormat.subject(REPORT))
            .isEqualTo("AI assistant weekly report: helpdesk, 12 Oct – 18 Oct 2026");
        assertThat(ReportFormat.text(REPORT)).isEqualTo("""
                AI assistant weekly report: helpdesk, 12 Oct – 18 Oct 2026
                Active users: 42 (+5% vs previous week)
                Conversations: 310 (−3%)
                Cost: USD 128.40 (+12%), USD 0.41 per conversation (+15%)
                Positive feedback: 82% (+3 pts)
                Knowledge base queries with no result: 7% (−1 pt)
                Top tools: search_orders (520 calls), create_ticket (210 calls)
                Projected cost for October 2026: USD 540.00 (USD 300.10 so far)
                Cost excludes 3 turns with unpriced models.
                Open the dashboard: https://example.com/ai-insights/
                """);
    }

    @Test
    void slackUsesMarkup() {
        assertThat(ReportFormat.slack(REPORT))
            .startsWith("*AI assistant weekly report: helpdesk, 12 Oct – 18 Oct 2026*\n")
            .contains("\n_Cost excludes 3 turns with unpriced models._\n")
            .endsWith("<https://example.com/ai-insights/|Open the dashboard>\n");
    }

    @Test
    void changeAgainstZero() {
        assertThat(ReportFormat.change(null, null)).isEmpty();
        assertThat(ReportFormat.change(5, null)).isEqualTo("new");
        assertThat(ReportFormat.change(0, 0)).isEqualTo("no change");
        assertThat(ReportFormat.change(5, 0)).isEqualTo("new");
        assertThat(ReportFormat.change(new BigDecimal("0.00"), new BigDecimal("0"))).isEqualTo("no change");
        assertThat(ReportFormat.change(100, 100)).isEqualTo("no change");
        assertThat(ReportFormat.change(0, 4)).isEqualTo("−100%");
        assertThat(ReportFormat.change(201, 200)).isEqualTo("+1%");
    }

    @Test
    void pointsChangeSingular() {
        assertThat(ReportFormat.pointsChange(null, 0.5)).isEmpty();
        assertThat(ReportFormat.pointsChange(0.5, null)).isEmpty();
        assertThat(ReportFormat.pointsChange(0.51, 0.5)).isEqualTo("+1 pt");
        assertThat(ReportFormat.pointsChange(0.4, 0.5)).isEqualTo("−10 pts");
        assertThat(ReportFormat.pointsChange(0.5, 0.5)).isEqualTo("no change");
    }

    @Test
    void slackEscapesData() {
        WeeklyReport report = report("<!channel> & co", List.of(tool("<b>", 1)), CURRENT, PREVIOUS, null);
        assertThat(ReportFormat.slack(report)).contains("&lt;!channel&gt; &amp; co")
            .contains("&lt;b&gt; (1 call)")
            .doesNotContain("<!channel>");
    }

    @Test
    void htmlEscapesData() {
        WeeklyReport report = report("a\"b'c", List.of(tool("<script>alert(1)</script>", 2)), CURRENT, PREVIOUS,
                URI.create("https://example.com/?a=1&b=2"));
        String html = ReportFormat.html(report);
        assertThat(html).doesNotContain("<script>")
            .contains("&lt;script&gt;alert(1)&lt;/script&gt;")
            .contains("a&quot;b&#39;c")
            .contains("href=\"https://example.com/?a=1&amp;b=2\"");
    }

    @Test
    void htmlIsACompleteDocument() {
        String html = ReportFormat.html(REPORT);
        assertThat(html).startsWith("<!doctype html>")
            .contains("<meta charset=\"utf-8\">")
            .contains("<title>AI assistant weekly report: helpdesk, 12 Oct – 18 Oct 2026</title>")
            .contains("USD 128.40")
            .contains("+12%")
            .contains("search_orders (520 calls)")
            .endsWith("</html>\n");
    }

    @Test
    void emptyReportRendersDashes() {
        String text = ReportFormat.text(report("helpdesk", List.of(), EMPTY, EMPTY, null));
        assertThat(text).contains("Active users: 0 (no change vs previous week)\n")
            .contains("Cost: USD 0.00 (no change)\n")
            .contains("Positive feedback: –\n")
            .contains("Knowledge base queries with no result: –\n")
            .contains("Top tools: none\n")
            .doesNotContain("NaN")
            .doesNotContain("Infinity");
    }

    @Test
    void optionalLinesOnlyWhenRelevant() {
        ReportFigures priced = new ReportFigures(1, 1, 1, BigDecimal.ONE, BigDecimal.ONE, null, null, 0);
        String text = ReportFormat.text(report("helpdesk", List.of(), priced, EMPTY, null));
        assertThat(text).doesNotContain("unpriced").doesNotContain("Open the dashboard");
        assertThat(ReportFormat.html(report("helpdesk", List.of(), priced, EMPTY, null))).doesNotContain("<a ");
    }

    @Test
    void firstDayShowsItsYearAcrossNewYear() {
        WeeklyReport report = new WeeklyReport("helpdesk", "USD", LocalDate.parse("2026-12-28"),
                LocalDate.parse("2027-01-03"), EMPTY, EMPTY, List.of(), PROJECTION, null);
        assertThat(ReportFormat.subject(report)).endsWith("28 Dec 2026 – 3 Jan 2027");
    }
}
