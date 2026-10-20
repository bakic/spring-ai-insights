package io.github.bakic.springaiinsights.report;

import java.math.BigDecimal;
import java.net.URI;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

final class ReportFixtures {

    static final ReportFigures FIGURES = new ReportFigures(3, 4, 8, new BigDecimal("10.00"), new BigDecimal("2.5"),
            0.75, 0.25, 0);

    static final WeeklyReport REPORT = new WeeklyReport("helpdesk", "USD", LocalDate.parse("2026-10-12"),
            LocalDate.parse("2026-10-18"), FIGURES, FIGURES, List.of(),
            new MonthProjection(YearMonth.of(2026, 10), BigDecimal.TEN, 18, new BigDecimal("17.22")),
            URI.create("https://example.com/ai-insights/"));

    private ReportFixtures() {
    }
}
