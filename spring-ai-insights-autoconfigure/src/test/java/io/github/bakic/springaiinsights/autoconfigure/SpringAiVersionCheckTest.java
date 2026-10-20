package io.github.bakic.springaiinsights.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class SpringAiVersionCheckTest {

    @Test
    void springAi200IsReportedWithTheVersionThatFixesIt() {
        assertThat(SpringAiVersionCheck.problem("2.0.0"))
                .contains("Spring AI 2.0.0")
                .contains("tool calls")
                .contains("knowledge base searches")
                .contains("2.0.1");
    }

    @Test
    void otherVersionsAreNotReported() {
        assertThat(SpringAiVersionCheck.problem("2.0.1")).isNull();
        assertThat(SpringAiVersionCheck.problem("2.1.0-M1")).isNull();
    }

    @Test
    void unknownVersionIsNotReported() {
        assertThat(SpringAiVersionCheck.problem(null)).isNull();
    }

    @Test
    void installedVersionIsReadFromTheSpringAiJar() {
        assertThat(SpringAiVersionCheck.installedVersion()).matches("\\d+\\.\\d+\\.\\d+.*");
    }

    @Test
    void affectedVersionLogsAWarning(CapturedOutput output) {
        SpringAiVersionCheck.warnIfAffected("2.0.0");

        assertThat(output).contains("WARN").contains("Spring AI 2.0.0");
    }

    @Test
    void startingOnTheTestedSpringAiVersionLogsNoWarning(CapturedOutput output) {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(InsightsAutoConfiguration.class))
                .run(context -> assertThat(context).hasNotFailed());

        assertThat(output).doesNotContain("Spring AI 2.0.0");
    }

}
