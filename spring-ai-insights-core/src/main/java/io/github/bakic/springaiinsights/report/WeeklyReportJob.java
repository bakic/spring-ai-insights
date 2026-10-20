package io.github.bakic.springaiinsights.report;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

/**
 * Sends last week's report once per application, whatever the number of instances. Never throws.
 */
public final class WeeklyReportJob implements Runnable {

    private static final Log log = LogFactory.getLog(WeeklyReportJob.class);

    static final Duration CLAIM = Duration.ofDays(35);

    private final WeeklyReports reports;

    private final ReportLock lock;

    private final List<ReportSender> senders;

    private final String application;

    private final Clock clock;

    public WeeklyReportJob(WeeklyReports reports, ReportLock lock, List<ReportSender> senders, String application,
            Clock clock) {
        this.reports = Objects.requireNonNull(reports, "reports");
        this.lock = Objects.requireNonNull(lock, "lock");
        this.senders = List.copyOf(senders);
        this.application = Objects.requireNonNull(application, "application");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public void run() {
        LocalDate firstDay = this.reports.lastWeekStart();
        String key = "weekly-report:" + this.application + ":" + firstDay;
        WeeklyReport report;
        try {
            if (!this.lock.claim(key, this.clock.instant().plus(CLAIM))) {
                log.debug("Weekly report " + key + " already claimed by another instance");
                return;
            }
            report = this.reports.forWeek(this.application, firstDay);
        }
        catch (RuntimeException ex) {
            log.warn("Weekly report for week of " + firstDay + " not built: " + ex);
            return;
        }
        for (ReportSender sender : this.senders) {
            try {
                sender.send(report);
                log.info("Weekly report for week of " + firstDay + " sent by " + sender.name());
            }
            catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                log.warn("Weekly report sending by " + sender.name() + " interrupted");
                return;
            }
            catch (Exception ex) {
                log.warn("Weekly report sending by " + sender.name() + " failed: " + ex);
            }
        }
    }

}
