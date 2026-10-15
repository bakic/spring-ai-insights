// Runnable helpdesk demo: a simulated model, tools, a help-center vector store and generated traffic.
// Not published. Run with ./gradlew :spring-ai-insights-demo:run
plugins {
    application
}

application {
    mainClass = "io.github.springaiinsights.demo.HelpdeskApplication"
}

dependencies {
    implementation(project(":spring-ai-insights-starter"))
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.ai:spring-ai-client-chat")
    implementation("org.springframework.ai:spring-ai-autoconfigure-model-tool")
    implementation("org.springframework.ai:spring-ai-vector-store")
    implementation("org.springframework.ai:spring-ai-vector-store-advisor")
    implementation("io.micrometer:micrometer-registry-prometheus")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-starter-security-test")
    testImplementation("org.awaitility:awaitility")
}
