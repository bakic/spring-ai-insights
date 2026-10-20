package io.github.bakic.springaiinsights.web;

import java.net.URI;
import java.time.Clock;
import java.time.ZoneId;
import java.util.List;

import io.github.bakic.springaiinsights.report.ReportFormat;
import io.github.bakic.springaiinsights.report.WeeklyReport;
import io.github.bakic.springaiinsights.report.WeeklyReports;
import io.github.bakic.springaiinsights.store.InMemoryEventStore;

/**
 * Prints last week's report of the generated preview data as plain text, Slack text and HTML. The configured models
 * come from the arguments (comma separated), by default the ones the dashboard preview configures. Run
 * {@code ./gradlew :spring-ai-insights-web:previewReport [-PconfiguredModels=gpt-4.1]}.
 */
public final class ReportPreview {

    private ReportPreview() {
    }

    public static void main(String[] args) {
        Clock clock = Clock.systemUTC();
        InMemoryEventStore store = new InMemoryEventStore(5000);
        PreviewData.seed(store, clock.instant());
        List<String> configured = args.length == 0 || args[0].isBlank() ? List.of("gpt-4.1-mini", "gpt-4.1")
                : List.of(args[0].split(","));
        WeeklyReport report = new WeeklyReports(store, "EUR", ZoneId.of("Europe/Paris"), clock,
                URI.create("https://app.example.com/ai-insights/"), configured)
            .latest("support-assistant");
        System.out.println("--- text ---");
        System.out.print(ReportFormat.text(report));
        System.out.println("--- slack ---");
        System.out.print(ReportFormat.slack(report));
        System.out.println("--- html ---");
        System.out.print(ReportFormat.html(report));
    }

}
