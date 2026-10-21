package io.github.bakic.springaiinsights.observability;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import static org.assertj.core.api.Assertions.assertThat;

/** Checks the OpenTelemetry guide against the configuration metadata of the real Spring Boot and Spring AI jars. */
class GuidePropertiesTest {

    static final Path GUIDE = Path.of("..", "docs", "opentelemetry.md");

    static final Path README = Path.of("..", "README.md");

    private static final Pattern YAML_BLOCK = Pattern.compile("```yaml\\n(.*?)```", Pattern.DOTALL);

    private static final Pattern GRADLE_DEPENDENCY = Pattern.compile("(?:implementation|runtimeOnly) '([^:']+):([^:']+)'");

    private static final Pattern RELATIVE_LINK = Pattern.compile("\\]\\((?!https?://|#|mailto:)([^)#\\s]+)(?:#[^)]*)?\\)");

    /** Property name to its metadata entry (type, deprecation). */
    private static final Map<String, Map<String, Object>> PROPERTIES = new HashMap<>();

    @BeforeAll
    @SuppressWarnings("unchecked")
    static void loadMetadata() throws IOException {
        Enumeration<URL> files = GuidePropertiesTest.class.getClassLoader()
            .getResources("META-INF/spring-configuration-metadata.json");
        while (files.hasMoreElements()) {
            try (InputStream in = files.nextElement().openStream()) {
                List<Map<String, Object>> properties = JsonPath.parse(in).read("$.properties[*]");
                for (Map<String, Object> property : properties) {
                    PROPERTIES.merge((String) property.get("name"), property, (a, b) -> a.containsKey("deprecation") ? b : a);
                }
            }
        }
        assertThat(PROPERTIES).containsKeys("management.opentelemetry.tracing.export.otlp.endpoint",
                "spring.ai.chat.observations.log-prompt", "ai-insights.enabled");
    }

    @Test
    void everyPropertyInTheGuideExistsAndIsNotDeprecated() throws IOException {
        List<String> keys = guideKeys();
        assertThat(keys).isNotEmpty();
        for (String key : keys) {
            Map<String, Object> property = resolve(key);
            assertThat(property).as("unknown property %s", key).isNotNull();
            assertThat(property).as("deprecated property %s", key).doesNotContainKey("deprecation");
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void boot3TracingNamesAppearOnlyAsTranslationsOfDeadProperties() throws IOException {
        assertThat(guideKeys()).noneMatch(key -> key.startsWith("management.otlp.tracing."));
        for (String key : List.of("management.otlp.tracing.endpoint", "management.otlp.tracing.headers")) {
            Map<String, Object> deprecation = (Map<String, Object>) PROPERTIES.get(key).get("deprecation");
            assertThat(deprecation).as(key).containsEntry("level", "error");
        }
    }

    @Test
    void everyGradleDependencyInTheGuideIsARealArtifact() throws IOException {
        Matcher m = GRADLE_DEPENDENCY.matcher(Files.readString(GUIDE));
        String classpath = System.getProperty("java.class.path");
        int found = 0;
        while (m.find()) {
            found++;
            if (m.group(1).equals("io.github.bakic.springaiinsights")) {
                assertThat(Path.of("..", m.group(2))).as(m.group()).isDirectory();
                continue;
            }
            assertThat(classpath).as("%s is not on the test classpath, so it was never resolved", m.group())
                .containsPattern("[/\\\\]" + Pattern.quote(m.group(2)) + "-[0-9][^/\\\\]*\\.jar");
        }
        assertThat(found).isPositive();
    }

    @Test
    void relativeLinksResolve() throws IOException {
        for (Path doc : List.of(GUIDE, README)) {
            Matcher m = RELATIVE_LINK.matcher(Files.readString(doc));
            while (m.find()) {
                assertThat(doc.getParent().resolve(m.group(1))).as("%s links to %s", doc, m.group(1)).exists();
            }
        }
    }

    /** The property itself, or the Map-typed property it is an entry of (for example {@code ...headers.Authorization}). */
    private static Map<String, Object> resolve(String key) {
        if (PROPERTIES.containsKey(key)) {
            return PROPERTIES.get(key);
        }
        for (int dot = key.lastIndexOf('.'); dot > 0; dot = key.lastIndexOf('.', dot - 1)) {
            Map<String, Object> parent = PROPERTIES.get(key.substring(0, dot));
            if (parent != null && String.valueOf(parent.get("type")).startsWith("java.util.Map")) {
                return parent;
            }
        }
        return null;
    }

    private static List<String> guideKeys() throws IOException {
        List<String> keys = new ArrayList<>();
        Matcher m = YAML_BLOCK.matcher(Files.readString(GUIDE));
        while (m.find()) {
            for (Object document : new Yaml().loadAll(m.group(1))) {
                if (document instanceof Map<?, ?> map) {
                    flatten("", map, keys);
                }
            }
        }
        return keys;
    }

    private static void flatten(String prefix, Map<?, ?> map, List<String> keys) {
        for (Map.Entry<?, ?> entry : new LinkedHashMap<>(map).entrySet()) {
            String key = prefix.isEmpty() ? String.valueOf(entry.getKey()) : prefix + "." + entry.getKey();
            if (entry.getValue() instanceof Map<?, ?> child) {
                flatten(key, child, keys);
            }
            else {
                keys.add(key);
            }
        }
    }
}
