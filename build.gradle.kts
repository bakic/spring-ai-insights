subprojects {
    apply(plugin = "java-library")

    group = rootProject.group
    version = rootProject.version

    repositories { mavenCentral() }

    configure<JavaPluginExtension> {
        toolchain { languageVersion = JavaLanguageVersion.of(21) }
        withSourcesJar()
        withJavadocJar()
    }

    val springBootVersion: String by project
    val springAiVersion: String by project

    dependencies {
        "api"(platform("org.springframework.boot:spring-boot-dependencies:$springBootVersion"))
        "api"(platform("org.springframework.ai:spring-ai-bom:$springAiVersion"))
        "testImplementation"("org.springframework.boot:spring-boot-starter-test")
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
    }

    tasks.withType<JavaCompile>().configureEach {
        options.compilerArgs.add("-parameters")
        options.encoding = "UTF-8"
    }
    tasks.withType<Javadoc>().configureEach { (options as StandardJavadocDocletOptions).addStringOption("Xdoclint:none", "-quiet") }
    tasks.withType<Test>().configureEach { useJUnitPlatform() }
}
