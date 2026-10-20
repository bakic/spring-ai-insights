package io.github.bakic.springaiinsights.autoconfigure;

import java.time.ZoneId;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;

import io.github.bakic.springaiinsights.report.InMemoryReportLock;
import io.github.bakic.springaiinsights.report.MailReportSender;
import io.github.bakic.springaiinsights.report.ReportLock;
import io.github.bakic.springaiinsights.report.SlackReportSender;
import io.github.bakic.springaiinsights.report.WeeklyReports;
import io.github.bakic.springaiinsights.analytics.InsightsAnalytics;
import io.github.bakic.springaiinsights.store.InsightsEventStore;

import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class InsightsReportAutoConfigurationTest {

    private static final String SLACK = "ai-insights.report.slack-webhook=https://hooks.slack.com/services/T/B/x";

    private final WebApplicationContextRunner web = new WebApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(InsightsAutoConfiguration.class,
                InsightsFeedbackAutoConfiguration.class, InsightsReportAutoConfiguration.class,
                InsightsWebAutoConfiguration.class))
        .withBean(SimpleMeterRegistry.class)
        .withBean(ObservationRegistry.class, ObservationRegistry::create);

    @Test
    void reportsBeanAlwaysPresent() {
        web.withPropertyValues("ai-insights.report.zone=Europe/Paris").run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(WeeklyReports.class);
            assertThat(context.getBean(WeeklyReports.class).zone()).isEqualTo(ZoneId.of("Europe/Paris"));
        });
    }

    @Test
    void noScheduleWithoutChannel() {
        web.run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(WeeklyReportSchedule.class));
    }

    @Test
    void scheduleWithSlackWebhook() {
        web.withPropertyValues(SLACK).run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(WeeklyReportSchedule.class)
                .hasSingleBean(SlackReportSender.class);
            WeeklyReportSchedule schedule = context.getBean(WeeklyReportSchedule.class);
            assertThat(schedule.cron()).isEqualTo("0 0 8 * * MON");
            assertThat(schedule.lock()).isInstanceOf(InMemoryReportLock.class);
        });
    }

    @Test
    void scheduleDisabled() {
        web.withPropertyValues(SLACK, "ai-insights.report.enabled=false")
            .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(WeeklyReportSchedule.class));
    }

    @Test
    void invalidCronFailsStartup() {
        web.withPropertyValues(SLACK, "ai-insights.report.cron=every monday")
            .run(context -> assertThat(context).getFailure().rootCause()
                .hasMessageContaining("ai-insights.report.cron"));
    }

    @Test
    void webhookMustBeHttp() {
        web.withPropertyValues("ai-insights.report.slack-webhook=file:///etc/passwd")
            .run(context -> assertThat(context).getFailure().rootCause()
                .hasMessageContaining("ai-insights.report.slack-webhook must be an absolute http(s) URL"));
    }

    @Test
    void malformedWebhookIsNeverPrinted() {
        for (String webhook : new String[] { "hooks.slack.com/services/T/B/s3cr3t",
                "https://hooks.slack.com/services/T/B/s3cr3t with space" }) {
            web.withPropertyValues("ai-insights.report.slack-webhook=" + webhook).run(context -> {
                Throwable failure = context.getStartupFailure();
                assertThat(failure).isNotNull();
                StringBuilder text = new StringBuilder();
                for (Throwable t = failure; t != null; t = t.getCause()) {
                    assertThat(t).as("a bind failure is reported with its value")
                        .isNotInstanceOf(org.springframework.boot.context.properties.bind.BindException.class);
                    text.append(t).append('\n');
                }
                assertThat(text.toString()).doesNotContain("s3cr3t")
                    .contains("ai-insights.report.slack-webhook must be an absolute http(s) URL");
            });
        }
    }

    @Test
    void emailWithoutMailSenderFailsStartup() {
        web.withPropertyValues("ai-insights.report.email-to=ceo@example.com")
            .run(context -> assertThat(context).getFailure().rootCause()
                .hasMessageContaining("spring-boot-starter-mail"));
    }

    @Test
    void emailWithoutMailClassesFailsStartup() {
        web.withClassLoader(new FilteredClassLoader(JavaMailSender.class))
            .withPropertyValues("ai-insights.report.email-to=ceo@example.com")
            .run(context -> assertThat(context).getFailure().rootCause()
                .hasMessageContaining("spring-boot-starter-mail"));
    }

    @Test
    void emailWithMailSenderSchedules() {
        web.withBean(JavaMailSender.class, JavaMailSenderImpl::new)
            .withPropertyValues("ai-insights.report.email-to[0]=ceo@example.com",
                    "ai-insights.report.email-to[1]=cto@example.com")
            .run(context -> assertThat(context).hasNotFailed()
                .hasSingleBean(MailReportSender.class)
                .hasSingleBean(WeeklyReportSchedule.class));
    }

    @Test
    void storeImplementingTheLockIsTheLock() {
        web.withBean(LockingStore.class, () -> mock(LockingStore.class))
            .withPropertyValues(SLACK, "ai-insights.identity.secret=k")
            .run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(WeeklyReportSchedule.class).lock())
                .isSameAs(context.getBean(InsightsEventStore.class));
        });
    }

    @Test
    void uniqueLockBeanWins() {
        ReportLock lock = (key, expiresAt) -> true;
        web.withBean(LockingStore.class, () -> mock(LockingStore.class))
            .withBean(ReportLock.class, () -> lock)
            .withPropertyValues(SLACK, "ai-insights.identity.secret=k")
            .run(context -> assertThat(context.getBean(WeeklyReportSchedule.class).lock()).isSameAs(lock));
    }

    @Test
    void reportEndpointServed() {
        web.withPropertyValues("ai-insights.dashboard.allow-anonymous=true", "spring.application.name=shop")
            .run(context -> {
                @SuppressWarnings("unchecked")
                RouterFunction<ServerResponse> routes = context.getBean("insightsDashboardRoutes",
                        RouterFunction.class);
                MockMvc mvc = MockMvcBuilders.routerFunctions(routes).build();
                mvc.perform(get("/ai-insights/api/report"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.application").value("shop"));
            });
    }

    interface LockingStore extends InsightsEventStore, InsightsAnalytics, ReportLock {
    }
}
