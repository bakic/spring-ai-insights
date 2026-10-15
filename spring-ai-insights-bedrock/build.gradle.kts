description = "Spring AI Insights support for Amazon Bedrock knowledge bases"

dependencies {
    api(project(":spring-ai-insights-core"))
    api("software.amazon.awssdk:bedrockagent")
}
