plugins { id("codearena.service") }

dependencies {
    implementation(project(":libs:codegen"))
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-security-oauth2-resource-server")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("io.awspring.cloud:spring-cloud-aws-starter-dynamodb")
    implementation("io.awspring.cloud:spring-cloud-aws-starter-s3")
    implementation("io.awspring.cloud:spring-cloud-aws-starter-sqs")
    // Plain Resilience4j (no Spring integration): one circuit breaker in code, no AOP needed
    implementation("io.github.resilience4j:resilience4j-circuitbreaker:${Versions.RESILIENCE4J}")
    implementation("com.github.f4b6a3:ulid-creator:${Versions.ULID}")
}