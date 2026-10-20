package io.github.bakic.springaiinsights.autoconfigure;

import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneId;
import java.util.List;

import io.github.bakic.springaiinsights.analytics.InsightsAnalytics;
import io.github.bakic.springaiinsights.pricing.PriceCatalog;
import io.github.bakic.springaiinsights.report.InMemoryReportLock;
import io.github.bakic.springaiinsights.report.MailReportSender;
import io.github.bakic.springaiinsights.report.ReportLock;
import io.github.bakic.springaiinsights.report.ReportSender;
import io.github.bakic.springaiinsights.report.SlackReportSender;
import io.github.bakic.springaiinsights.report.WeeklyReportJob;
import io.github.bakic.springaiinsights.report.WeeklyReports;
import io.github.bakic.springaiinsights.store.InMemoryEventStore;
import io.github.bakic.springaiinsights.store.InsightsEventStore;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionOutcome;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.SpringBootCondition;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.util.StringUtils;

/** Builds the weekly report and, when a channel is configured, sends it on a schedule. */
@AutoConfiguration(after = InsightsFeedbackAutoConfiguration.class)
@ConditionalOnBooleanProperty(name = "ai-insights.enabled", matchIfMissing = true)
public class InsightsReportAutoConfiguration {

    private static final Log logger = LogFactory.getLog(InsightsReportAutoConfiguration.class);

    private static final String MAIL_MISSING = "ai-insights.report.email-to needs a JavaMailSender: add "
            + "spring-boot-starter-mail and set spring.mail.host";

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean({ InsightsAnalytics.class, PriceCatalog.class })
    WeeklyReports insightsWeeklyReports(InsightsAnalytics analytics, PriceCatalog prices,
            InsightsProperties properties) {
        InsightsProperties.Report report = properties.getReport();
        return new WeeklyReports(analytics, prices.currency(), zone(report), Clock.systemUTC(),
                report.getDashboardUrl());
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBooleanProperty(name = "ai-insights.report.enabled", matchIfMissing = true)
    @Conditional(SlackConfigured.class)
    SlackReportSender insightsSlackReportSender(InsightsProperties properties) {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        return new SlackReportSender(properties.getReport().slackWebhookUri(), client);
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    @ConditionalOnBean({ WeeklyReports.class, InsightsEventStore.class })
    @ConditionalOnBooleanProperty(name = "ai-insights.report.enabled", matchIfMissing = true)
    @Conditional(ChannelConfigured.class)
    WeeklyReportSchedule insightsWeeklyReportSchedule(WeeklyReports reports, InsightsEventStore store,
            ObjectProvider<ReportLock> locks, ObjectProvider<ReportSender> senders, InsightsProperties properties,
            Environment environment) {
        InsightsProperties.Report report = properties.getReport();
        List<ReportSender> channels = senders.orderedStream().toList();
        if (!report.getEmailTo().isEmpty() && channels.stream().noneMatch(sender -> "email".equals(sender.name()))) {
            throw new IllegalStateException(MAIL_MISSING);
        }
        ReportLock lock = lock(store, locks);
        WeeklyReportJob job = new WeeklyReportJob(reports, lock, channels,
                InsightsAutoConfiguration.application(properties, environment), Clock.systemUTC());
        return new WeeklyReportSchedule(job, report.getCron(), zone(report), lock);
    }

    /** A unique {@link ReportLock} bean, else the store when it is one, else a lock local to this instance. */
    private static ReportLock lock(InsightsEventStore store, ObjectProvider<ReportLock> locks) {
        List<ReportLock> declared = locks.orderedStream().filter(lock -> lock != store).toList();
        if (declared.size() == 1) {
            return declared.get(0);
        }
        if (store instanceof ReportLock storeLock) {
            return storeLock;
        }
        if (!(store instanceof InMemoryEventStore)) {
            logger.warn("The Spring AI Insights event store " + store.getClass().getName()
                    + " is not a ReportLock: each instance will send the weekly report. Declare a ReportLock bean.");
        }
        return new InMemoryReportLock();
    }

    private static ZoneId zone(InsightsProperties.Report report) {
        ZoneId zone = report.getZone();
        return zone == null ? ZoneId.systemDefault() : zone;
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = { "org.springframework.mail.javamail.JavaMailSender",
            "jakarta.mail.internet.MimeMessage" })
    @ConditionalOnBooleanProperty(name = "ai-insights.report.enabled", matchIfMissing = true)
    @Conditional(EmailConfigured.class)
    static class Mail {

        @Bean
        @ConditionalOnMissingBean
        MailReportSender insightsMailReportSender(ObjectProvider<JavaMailSender> mail, InsightsProperties properties) {
            JavaMailSender sender = mail.getIfUnique();
            if (sender == null) {
                throw new IllegalStateException(MAIL_MISSING);
            }
            InsightsProperties.Report report = properties.getReport();
            return new MailReportSender(sender, report.getEmailFrom(), report.getEmailTo());
        }
    }

    private static boolean slack(ConditionContext context) {
        return StringUtils.hasText(context.getEnvironment().getProperty("ai-insights.report.slack-webhook"));
    }

    private static boolean email(ConditionContext context) {
        return Binder.get(context.getEnvironment())
            .bind("ai-insights.report.email-to", Bindable.listOf(String.class))
            .map(to -> to.stream().anyMatch(StringUtils::hasText))
            .orElse(false);
    }

    static class SlackConfigured extends SpringBootCondition {

        @Override
        public ConditionOutcome getMatchOutcome(ConditionContext context, AnnotatedTypeMetadata metadata) {
            return slack(context) ? ConditionOutcome.match("Slack webhook configured")
                    : ConditionOutcome.noMatch("no ai-insights.report.slack-webhook");
        }
    }

    static class EmailConfigured extends SpringBootCondition {

        @Override
        public ConditionOutcome getMatchOutcome(ConditionContext context, AnnotatedTypeMetadata metadata) {
            return email(context) ? ConditionOutcome.match("report email recipients configured")
                    : ConditionOutcome.noMatch("no ai-insights.report.email-to");
        }
    }

    static class ChannelConfigured extends SpringBootCondition {

        @Override
        public ConditionOutcome getMatchOutcome(ConditionContext context, AnnotatedTypeMetadata metadata) {
            return (slack(context) || email(context)) ? ConditionOutcome.match("a report channel is configured")
                    : ConditionOutcome.noMatch("no ai-insights.report channel");
        }
    }
}
