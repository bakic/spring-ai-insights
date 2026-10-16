description = "Spring AI Insights support for Amazon Bedrock knowledge bases and pricing"

dependencies {
    api(project(":spring-ai-insights-core"))
    api("software.amazon.awssdk:bedrockagent")
    api("software.amazon.awssdk:pricing")
    api("software.amazon.awssdk:bedrock")
    compileOnly("software.amazon.awssdk:bedrockagentruntime")
    testImplementation("software.amazon.awssdk:bedrockagentruntime")
}
