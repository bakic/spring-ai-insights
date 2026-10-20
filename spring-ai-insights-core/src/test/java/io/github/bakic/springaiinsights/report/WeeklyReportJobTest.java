package io.github.bakic.springaiinsights.report;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import io.github.bakic.springaiinsights.analytics.InsightsAnalytics;
import io.github.bakic.springaiinsights.analytics.QualitySummary;
import io.github.bakic.springaiinsights.analytics.UsageSummary;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WeeklyReportJobTest {

    private static final ZoneId PARIS = ZoneId.of("Europe/Paris");

    private final Clock clock = Clock.fixed(Instant.parse("2026-10-19T06:00:00Z"), PARIS);

    private final InsightsAnalytics analytics = mock(InsightsAnalytics.class);

    private final WeeklyReports reports = new WeeklyReports(analytics, "USD", PARIS, clock, null);

    private final List<String> deliveries = new ArrayList<>();

    WeeklyReportJobTest() {
        when(analytics.summary(any())).thenReturn(new UsageSummary(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, BigDecimal.ZERO, 0,
                0, null, 0, 0, 0, 0));
        when(analytics.quality(any())).thenReturn(new QualitySummary(0, 0, List.of(), List.of()));
        when(analytics.tools(any())).thenReturn(List.of());
    }

    private ReportSender sender(String name) {
        return new ReportSender() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public void send(WeeklyReport report) {
                deliveries.add(name + ":" + report.application() + ":" + report.firstDay());
            }
        };
    }

    private ReportSender failing(String name) {
        return new ReportSender() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public void send(WeeklyReport report) throws Exception {
                throw new java.io.IOException("down");
            }
        };
    }

    @Test
    void sendsOncePerWeek() {
        ReportLock lock = new InMemoryReportLock(clock);
        new WeeklyReportJob(reports, lock, List.of(sender("slack")), "helpdesk", clock).run();
        new WeeklyReportJob(reports, lock, List.of(sender("slack")), "helpdesk", clock).run();
        assertThat(deliveries).containsExactly("slack:helpdesk:2026-10-12");
    }

    @Test
    void claimsThePeriodPerApplication() {
        List<String> keys = new ArrayList<>();
        List<Instant> expiries = new ArrayList<>();
        ReportLock lock = (key, expiresAt) -> {
            keys.add(key);
            expiries.add(expiresAt);
            return true;
        };
        new WeeklyReportJob(reports, lock, List.of(sender("slack")), "helpdesk", clock).run();
        assertThat(keys).containsExactly("weekly-report:helpdesk:2026-10-12");
        assertThat(expiries).containsExactly(Instant.parse("2026-11-23T06:00:00Z"));
    }

    @Test
    void oneFailingSenderDoesNotStopOthers() {
        new WeeklyReportJob(reports, new InMemoryReportLock(clock), List.of(failing("slack"), sender("email")),
                "helpdesk", clock)
            .run();
        assertThat(deliveries).containsExactly("email:helpdesk:2026-10-12");
    }

    @Test
    void lockFailureIsLoggedNotThrown() {
        ReportLock lock = (key, expiresAt) -> {
            throw new IllegalStateException("store down");
        };
        WeeklyReportJob job = new WeeklyReportJob(reports, lock, List.of(sender("slack")), "helpdesk", clock);
        assertThatNoException().isThrownBy(job::run);
        assertThat(deliveries).isEmpty();
    }

    @Test
    void storeFailureIsLoggedNotThrown() {
        when(analytics.summary(any())).thenThrow(new IllegalStateException("store down"));
        WeeklyReportJob job = new WeeklyReportJob(reports, new InMemoryReportLock(clock), List.of(sender("slack")),
                "helpdesk", clock);
        assertThatNoException().isThrownBy(job::run);
        assertThat(deliveries).isEmpty();
    }

    @Test
    void releasesTheClaimWhenNoSenderDelivers() {
        ReportLock lock = new InMemoryReportLock(clock);
        new WeeklyReportJob(reports, lock, List.of(failing("slack"), failing("email")), "helpdesk", clock).run();
        new WeeklyReportJob(reports, lock, List.of(sender("slack")), "helpdesk", clock).run();
        assertThat(deliveries).containsExactly("slack:helpdesk:2026-10-12");
    }

    @Test
    void releasesTheClaimWhenTheReportCannotBeBuilt() {
        ReportLock lock = new InMemoryReportLock(clock);
        when(analytics.summary(any())).thenThrow(new IllegalStateException("store down"));
        new WeeklyReportJob(reports, lock, List.of(sender("slack")), "helpdesk", clock).run();
        doReturn(new UsageSummary(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, BigDecimal.ZERO, 0, 0, null, 0, 0, 0, 0))
            .when(analytics)
            .summary(any());
        new WeeklyReportJob(reports, lock, List.of(sender("slack")), "helpdesk", clock).run();
        assertThat(deliveries).containsExactly("slack:helpdesk:2026-10-12");
    }

    @Test
    void keepsTheClaimOnceOneSenderDelivered() {
        ReportLock lock = new InMemoryReportLock(clock);
        new WeeklyReportJob(reports, lock, List.of(failing("slack"), sender("email")), "helpdesk", clock).run();
        new WeeklyReportJob(reports, lock, List.of(sender("email")), "helpdesk", clock).run();
        assertThat(deliveries).containsExactly("email:helpdesk:2026-10-12");
    }

    @Test
    void aFailingReleaseIsLoggedNotThrown() {
        ReportLock lock = new ReportLock() {
            @Override
            public boolean claim(String key, Instant expiresAt) {
                return true;
            }

            @Override
            public void release(String key) {
                throw new IllegalStateException("store down");
            }
        };
        WeeklyReportJob job = new WeeklyReportJob(reports, lock, List.of(failing("slack")), "helpdesk", clock);
        assertThatNoException().isThrownBy(job::run);
    }
}
