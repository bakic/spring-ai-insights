description = "Spring AI Insights dashboard and feedback endpoint for Spring MVC applications"

dependencies {
    api(project(":spring-ai-insights-core"))
    compileOnly("org.springframework:spring-webmvc")
    compileOnly("jakarta.servlet:jakarta.servlet-api")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation(testFixtures(project(":spring-ai-insights-core")))
}

tasks.register<JavaExec>("previewDashboard") {
    description = "Serves the dashboard with generated data on http://localhost:8089/ai-insights/"
    group = "application"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("io.github.springaiinsights.web.DashboardPreview")
}

val testDashboardScripts by tasks.registering(Exec::class) {
    description = "Runs the dashboard script tests with Node.js 20 or later"
    group = "verification"
    inputs.dir("src/main/resources/ai-insights-dashboard")
    inputs.dir("src/test/js")
    commandLine("node", "--test", "src/test/js/markdown.test.mjs")
}

tasks.named("check") {
    dependsOn(testDashboardScripts)
}
