package io.github.bakic.springaiinsights.autoconfigure;

import java.time.ZoneId;

import io.github.bakic.springaiinsights.report.ReportLock;

import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.support.CronTrigger;

/** Runs the weekly report job on its own daemon thread, so that it never competes with the application's tasks. */
public final class WeeklyReportSchedule implements AutoCloseable {

    private final String cron;

    private final ReportLock lock;

    private final ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();

    WeeklyReportSchedule(Runnable job, String cron, ZoneId zone, ReportLock lock) {
        this.cron = cron;
        this.lock = lock;
        this.scheduler.setPoolSize(1);
        this.scheduler.setDaemon(true);
        this.scheduler.setThreadNamePrefix("ai-insights-report-");
        this.scheduler.initialize();
        this.scheduler.schedule(job, new CronTrigger(cron, zone));
    }

    String cron() {
        return cron;
    }

    ReportLock lock() {
        return lock;
    }

    @Override
    public void close() {
        this.scheduler.shutdown();
    }
}
