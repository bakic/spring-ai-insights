package io.github.springaiinsights.report;

/**
 * Delivers a {@link WeeklyReport} to one channel.
 */
public interface ReportSender {

    /**
     * Short channel name used in logs.
     * @return the name, such as {@code slack}
     */
    String name();

    /**
     * Delivers the report.
     * @param report the report
     * @throws Exception when delivery fails
     */
    void send(WeeklyReport report) throws Exception;

}
