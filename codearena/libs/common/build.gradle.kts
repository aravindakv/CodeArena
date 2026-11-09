plugins { id("codearena.java-library") }

// compileOnly: each service brings the runtime dependency it actually uses
// (servlet vs reactive web, DynamoDB or not), so this library never forces one on a service.
dependencies {
    api("tools.jackson.core:jackson-databind")   // Jackson 3 (Boot 4 default); annotations keep com.fasterxml
    compileOnly("org.springframework:spring-web")
    compileOnly("org.springframework.security:spring-security-oauth2-resource-server")
    compileOnly("org.springframework.security:spring-security-oauth2-jose")
    compileOnly("software.amazon.awssdk:dynamodb")
}