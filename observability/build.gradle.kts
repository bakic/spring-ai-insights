// Test-only: proves the dashboard, alert rules and OpenTelemetry guide against real Prometheus and Grafana.
// Holds no main code and must not be published.
dependencies {
    testImplementation(project(":spring-ai-insights-core"))
    testImplementation("io.micrometer:micrometer-registry-prometheus")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.yaml:snakeyaml")
    testImplementation("com.jayway.jsonpath:json-path")
}
