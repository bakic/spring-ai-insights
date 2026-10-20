package io.github.bakic.springaiinsights.observability;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.testcontainers.containers.ContainerLaunchException;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.output.ToStringConsumer;
import org.testcontainers.containers.startupcheck.OneShotStartupCheckStrategy;
import org.testcontainers.utility.MountableFile;
import org.yaml.snakeyaml.Yaml;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

class AlertRulesTest {

    static final String PROMETHEUS_IMAGE = "prom/prometheus:v3.5.0";

    static final Path RULES_DIR = Path.of("prometheus");

    static final Path RULES = RULES_DIR.resolve("ai-insights-alerts.yml");

    @Test
    void promtoolAcceptsTheRules() {
        assertThat(promtool("check", "rules", "ai-insights-alerts.yml")).contains("SUCCESS: 5 rules found");
    }

    @Test
    void promtoolUnitTestsPass() {
        assertThat(promtool("test", "rules", "ai-insights-alerts.test.yml")).contains("SUCCESS");
    }

    @Test
    void everyAlertUsesExportedMetrics() throws IOException {
        for (Map<String, Object> rule : rules()) {
            String expr = (String) rule.get("expr");
            assertThat(SampleScrape.metricNamesIn(expr)).as("%s", rule.get("alert"))
                .isNotEmpty()
                .isSubsetOf(SampleScrape.metricNames());
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void everyAlertIsLabelledAndAnnotated() throws IOException {
        List<Map<String, Object>> rules = rules();
        assertThat(rules).extracting(r -> r.get("alert")).containsExactly("AiInsightsCostSpike",
                "AiInsightsToolErrorRate", "AiInsightsEmptyRetrievalRate", "AiInsightsSlowFirstToken",
                "AiInsightsEventsLost");
        for (Map<String, Object> rule : rules) {
            Map<String, String> labels = (Map<String, String>) rule.get("labels");
            Map<String, String> annotations = (Map<String, String>) rule.get("annotations");
            assertThat(labels.get("severity")).as("%s", rule.get("alert")).isIn("warning", "critical");
            assertThat(annotations.get("summary")).contains("{{ $labels.application }}");
            assertThat(annotations.get("description")).contains("{{ $labels.application }}");
        }
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> rules() throws IOException {
        try (Reader reader = Files.newBufferedReader(RULES)) {
            Map<String, Object> file = new Yaml().load(reader);
            List<Map<String, Object>> rules = new ArrayList<>();
            for (Map<String, Object> group : (List<Map<String, Object>>) file.get("groups")) {
                rules.addAll((List<Map<String, Object>>) group.get("rules"));
            }
            return rules;
        }
    }

    private static String promtool(String... args) {
        ToStringConsumer output = new ToStringConsumer();
        try (GenericContainer<?> container = new GenericContainer<>(PROMETHEUS_IMAGE)
            .withCopyFileToContainer(MountableFile.forHostPath(RULES_DIR), "/rules")
            .withWorkingDirectory("/rules")
            .withCreateContainerCmdModifier(cmd -> cmd.withEntrypoint("promtool"))
            .withCommand(args)
            .withLogConsumer(output)
            .withStartupCheckStrategy(new OneShotStartupCheckStrategy().withTimeout(Duration.ofMinutes(3)))) {
            container.start();
            return output.toUtf8String();
        }
        catch (ContainerLaunchException e) {
            return fail("promtool %s failed:%n%s", String.join(" ", args), output.toUtf8String());
        }
    }
}
