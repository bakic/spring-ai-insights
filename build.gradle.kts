plugins {
    id("com.vanniktech.maven.publish") version "0.37.0" apply false
}

subprojects {
    apply(plugin = "java-library")

    group = rootProject.group
    version = rootProject.version

    repositories { mavenCentral() }

    configure<JavaPluginExtension> {
        toolchain { languageVersion = JavaLanguageVersion.of(21) }
    }

    val springBootVersion: String by project
    val springAiVersion: String by project
    val awsSdkVersion: String by project

    // The BOMs only pick versions inside this build. Published modules carry resolved versions instead, so
    // adding the starter does not impose Spring Boot's or Spring AI's version catalog on an application.
    val managedVersions = configurations.create("managedVersions") {
        isCanBeConsumed = false
        isCanBeResolved = false
    }
    configurations.matching { it.name.endsWith("Classpath") || it.name.endsWith("nnotationProcessor") }
        .configureEach { extendsFrom(managedVersions) }

    dependencies {
        "managedVersions"(platform("org.springframework.boot:spring-boot-dependencies:$springBootVersion"))
        "managedVersions"(platform("org.springframework.ai:spring-ai-bom:$springAiVersion"))
        "managedVersions"(platform("software.amazon.awssdk:bom:$awsSdkVersion"))
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

val publishedModules = listOf(
    "spring-ai-insights-core",
    "spring-ai-insights-mongodb",
    "spring-ai-insights-bedrock",
    "spring-ai-insights-web",
    "spring-ai-insights-autoconfigure",
    "spring-ai-insights-starter",
)

val stagingRepo = layout.buildDirectory.dir("staging-repo")

val cleanStagingRepo = tasks.register<Delete>("cleanStagingRepo") { delete(stagingRepo) }

val projectUrl: String by project

configure(subprojects.filter { it.name in publishedModules }) {
    apply(plugin = "com.vanniktech.maven.publish")

    configure<com.vanniktech.maven.publish.MavenPublishBaseExtension> {
        publishToMavenCentral()
        if (providers.gradleProperty("signingInMemoryKey").isPresent) {
            signAllPublications()
        }
        coordinates(group.toString(), name, version.toString())
        pom {
            name.set(project.name)
            description.set(provider { project.description })
            url.set(projectUrl)
            licenses {
                license {
                    name.set("The Apache License, Version 2.0")
                    url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                    distribution.set("repo")
                }
            }
            developers {
                developer {
                    id.set("springaiinsights")
                    name.set("Spring AI Insights contributors")
                    url.set(projectUrl)
                }
            }
            scm {
                url.set(projectUrl)
                connection.set("scm:git:$projectUrl.git")
                developerConnection.set("scm:git:$projectUrl.git")
            }
        }
    }

    configure<PublishingExtension> {
        publications.withType<MavenPublication>().configureEach {
            versionMapping {
                usage("java-api") { fromResolutionOf("compileClasspath") }
                usage("java-runtime") { fromResolutionResult() }
            }
        }
        repositories {
            maven {
                name = "staging"
                url = uri(stagingRepo)
            }
        }
    }

    // Test fixtures are for this build's own tests, not a public API.
    plugins.withId("java-test-fixtures") {
        val java = components["java"] as AdhocComponentWithVariants
        afterEvaluate {
            listOf("testFixturesApiElements", "testFixturesRuntimeElements", "testFixturesSourcesElements")
                .mapNotNull { configurations.findByName(it) }
                .forEach { java.withVariantsFromConfiguration(it) { skip() } }
        }
    }

    tasks.matching { it.name.startsWith("publish") && it.name.endsWith("ToStagingRepository") }.configureEach { dependsOn(cleanStagingRepo) }
}

// Publishes to build/staging-repo and checks it the way Maven Central validates a deployment.
tasks.register("verifyPublication") {
    group = "verification"
    description = "Publishes the library modules to build/staging-repo and checks them against Maven Central rules."
    dependsOn(publishedModules.map { ":$it:publishAllPublicationsToStagingRepository" })
    val repo = stagingRepo
    val modules = publishedModules
    val projectVersion = version.toString()
    val groupPath = project.group.toString().replace('.', '/')
    doLast {
        val root = repo.get().asFile.resolve(groupPath)
        val errors = mutableListOf<String>()
        val unexpected = (root.list() ?: emptyArray()).filter { it !in modules }
        if (unexpected.isNotEmpty()) errors += "unexpected published modules: $unexpected"
        val base = projectVersion.removeSuffix("-SNAPSHOT")
        for (module in modules) {
            val files = root.resolve("$module/$projectVersion").listFiles()?.map { it.name }.orEmpty()
                .filter { it.startsWith("$module-$base") }
            fun has(suffix: String, plain: Boolean = false) = files.any {
                it.endsWith(suffix) && (!plain || !(it.endsWith("-sources.jar") || it.endsWith("-javadoc.jar")))
            }
            if (!has(".jar", plain = true)) errors += "$module: no jar"
            if (!has("-sources.jar")) errors += "$module: no sources jar"
            if (!has("-javadoc.jar")) errors += "$module: no javadoc jar"
            if (!has(".module")) errors += "$module: no Gradle module metadata"
            files.filter { it.contains("test-fixtures") && (it.endsWith(".jar") || it.endsWith(".pom")) }.forEach { errors += "$module: publishes $it" }
            val pomFile = files.filter { it.endsWith(".pom") }.maxOrNull()
            if (pomFile == null) {
                errors += "$module: no pom"
                continue
            }
            val pom = javax.xml.parsers.DocumentBuilderFactory.newInstance()
                .newDocumentBuilder().parse(root.resolve("$module/$projectVersion/$pomFile"))
            val xpath = javax.xml.xpath.XPathFactory.newInstance().newXPath()
            fun text(path: String) = xpath.evaluate(path, pom).trim()
            for (path in listOf("/project/name", "/project/description", "/project/url",
                    "/project/developers/developer/id", "/project/scm/url", "/project/scm/connection")) {
                if (text(path).isEmpty()) errors += "$module: pom has no ${path.removePrefix("/project/")}"
            }
            if (text("/project/licenses/license/name") != "The Apache License, Version 2.0") {
                errors += "$module: pom license is '${text("/project/licenses/license/name")}'"
            }
            if (text("count(/project/dependencyManagement)") != "0") errors += "$module: pom has dependencyManagement"
            val unversioned = xpath.evaluate("/project/dependencies/dependency[not(version)]/artifactId", pom,
                javax.xml.xpath.XPathConstants.NODESET) as org.w3c.dom.NodeList
            if (unversioned.length > 0) errors += "$module: dependencies without version: ${unversioned.item(0).textContent}"
        }
        if (errors.isNotEmpty()) throw GradleException("Publication is not Maven Central ready:\n  " + errors.joinToString("\n  "))
    }
}
