dependencies {
    api(project(":spring-ai-insights-core"))
    api("org.springframework.boot:spring-boot-autoconfigure")
    implementation("org.springframework.ai:spring-ai-autoconfigure-model-chat-client")
    compileOnly("org.springframework.security:spring-security-core")
    compileOnly("org.springframework.security:spring-security-oauth2-jose")
    annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")
    testImplementation("org.springframework.boot:spring-boot-starter-actuator")
    testImplementation("org.springframework.security:spring-security-core")
    testImplementation("org.springframework.security:spring-security-oauth2-jose")
    testImplementation("io.projectreactor:reactor-test")
}
