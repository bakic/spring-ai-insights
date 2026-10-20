package io.github.bakic.springaiinsights.observability;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DashboardTest {

    static final Path DASHBOARD = Path.of("grafana", "ai-insights-dashboard.json");

    private static final Pattern SELECTOR = Pattern.compile("(ai_insights_[a-z_]+)(\\{[^}]*})?");

    static DocumentContext dashboard() throws IOException {
        return JsonPath.parse(Files.readString(DASHBOARD));
    }

    static List<String> expressions() throws IOException {
        return dashboard().read("$..targets[*].expr");
    }

    @Test
    void identity() throws IOException {
        DocumentContext d = dashboard();
        assertThat(d.read("$.uid", String.class)).isEqualTo("ai-insights");
        assertThat(d.read("$.title", String.class)).isEqualTo("Spring AI Insights");
        assertThat(d.read("$.templating.list[0].name", String.class)).isEqualTo("datasource");
        assertThat(d.read("$.templating.list[0].type", String.class)).isEqualTo("datasource");
        assertThat(d.read("$.templating.list[0].query", String.class)).isEqualTo("prometheus");
        assertThat(d.read("$.templating.list[1].name", String.class)).isEqualTo("application");
        assertThat(d.read("$.templating.list[1].query", String.class))
            .isEqualTo("label_values(ai_insights_turns_total, application)");
        assertThat(d.read("$.templating.list[1].multi", Boolean.class)).isTrue();
        assertThat(d.read("$.templating.list[1].includeAll", Boolean.class)).isTrue();
        assertThat(d.read("$.templating.list[1].datasource.uid", String.class)).isEqualTo("${datasource}");
    }

    @Test
    void panelIdsAreUnique() throws IOException {
        List<Integer> ids = dashboard().read("$.panels[*].id");
        assertThat(ids).isNotEmpty().doesNotHaveDuplicates();
    }

    @Test
    void everyTargetUsesTheVariables() throws IOException {
        List<Map<String, Object>> panels = dashboard().read("$.panels[?(@.targets)]");
        assertThat(panels).isNotEmpty();
        for (Map<String, Object> panel : panels) {
            assertThat(JsonPath.parse(panel).read("$.datasource.uid", String.class)).as("%s", panel.get("title"))
                .isEqualTo("${datasource}");
            List<String> uids = JsonPath.parse(panel).read("$.targets[*].datasource.uid");
            assertThat(uids).as("%s", panel.get("title")).containsOnly("${datasource}");
        }
        for (String expr : expressions()) {
            Matcher m = SELECTOR.matcher(expr);
            int selectors = 0;
            while (m.find()) {
                selectors++;
                assertThat(m.group(2)).as("selector %s in %s", m.group(1), expr)
                    .isNotNull()
                    .contains("application=~\"$application\"");
            }
            assertThat(selectors).as(expr).isPositive();
        }
    }

    @Test
    void everyQueryUsesExportedMetrics() throws IOException {
        for (String expr : expressions()) {
            assertThat(SampleScrape.metricNamesIn(expr)).as(expr).isSubsetOf(SampleScrape.metricNames());
        }
    }
}
