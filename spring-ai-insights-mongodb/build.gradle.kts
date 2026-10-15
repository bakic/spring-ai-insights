description = "Spring AI Insights MongoDB store"

dependencies {
    api(project(":spring-ai-insights-core"))
    compileOnly("org.springframework.data:spring-data-mongodb")
    compileOnly("org.mongodb:mongodb-driver-sync")
    testImplementation("org.springframework.data:spring-data-mongodb")
    testImplementation("org.mongodb:mongodb-driver-sync")
    testImplementation(testFixtures(project(":spring-ai-insights-core")))
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-mongodb")
}

tasks.withType<Test>().configureEach {
    providers.gradleProperty("mongoImage").orNull?.let { systemProperty("ai-insights.test.mongo-image", it) }
}
