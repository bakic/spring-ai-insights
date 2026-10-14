plugins {
    `java-test-fixtures`
}

dependencies {
    api("org.springframework.ai:spring-ai-client-chat")
    api("org.springframework.ai:spring-ai-model")
    api("org.springframework.ai:spring-ai-vector-store")
    api("io.micrometer:micrometer-core")
    api("io.micrometer:micrometer-observation")
    api("io.projectreactor:reactor-core")
    api("org.jspecify:jspecify")
    testImplementation("io.projectreactor:reactor-test")
    testImplementation("io.micrometer:micrometer-observation-test")
    testFixturesApi("org.junit.jupiter:junit-jupiter-api")
    testFixturesApi("org.assertj:assertj-core")
}
