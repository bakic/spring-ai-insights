pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

rootProject.name = "spring-ai-insights"

include(
    "spring-ai-insights-core",
    "spring-ai-insights-mongodb",
    "spring-ai-insights-bedrock",
    "spring-ai-insights-web",
    "spring-ai-insights-autoconfigure",
    "spring-ai-insights-starter",
)

// Test-only project holding the Grafana dashboard, alert rules and their tests; never published.
include("spring-ai-insights-observability")
project(":spring-ai-insights-observability").projectDir = file("observability")

// Runnable helpdesk demo with a simulated model; never published.
include("spring-ai-insights-demo")
project(":spring-ai-insights-demo").projectDir = file("demo")
