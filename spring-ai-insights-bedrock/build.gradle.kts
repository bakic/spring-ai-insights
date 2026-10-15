val awsSdkVersion: String by project

dependencies {
    api(project(":spring-ai-insights-core"))
    api(platform("software.amazon.awssdk:bom:$awsSdkVersion"))
    api("software.amazon.awssdk:bedrockagent")
}
