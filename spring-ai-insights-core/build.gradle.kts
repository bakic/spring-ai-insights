plugins {
    `java-test-fixtures`
}

description = "Spring AI Insights core: capture advisor, event model, pricing, metrics, analytics and stores API"

dependencies {
    api("org.springframework.ai:spring-ai-client-chat")
    api("org.springframework.ai:spring-ai-model")
    api("org.springframework.ai:spring-ai-vector-store")
    api("io.micrometer:micrometer-core")
    api("io.micrometer:micrometer-observation")
    api("io.projectreactor:reactor-core")
    api("org.jspecify:jspecify")
    compileOnly("org.springframework:spring-context-support")
    compileOnly("jakarta.mail:jakarta.mail-api")
    testImplementation("io.projectreactor:reactor-test")
    testImplementation("org.springframework:spring-context-support")
    testImplementation("jakarta.mail:jakarta.mail-api")
    testRuntimeOnly("org.eclipse.angus:angus-mail")
    testImplementation("io.micrometer:micrometer-observation-test")
    testImplementation("io.micrometer:micrometer-registry-prometheus")
    testFixturesApi("org.junit.jupiter:junit-jupiter-api")
    testFixturesApi("org.assertj:assertj-core")
}
