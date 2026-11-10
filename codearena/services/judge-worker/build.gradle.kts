plugins { id("codearena.service") }

dependencies {
    implementation(project(":libs:codegen"))
    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.springframework:spring-web")                 // RestClient only, no web server
    implementation("io.awspring.cloud:spring-cloud-aws-starter-sqs")
    implementation("io.awspring.cloud:spring-cloud-aws-starter-s3")
    implementation("io.awspring.cloud:spring-cloud-aws-starter-dynamodb")
}

// Tests tagged "sandbox" need Docker and the runner images: ./gradlew :services:judge-worker:test -Psandbox
tasks.withType<Test>().configureEach {
    useJUnitPlatform {
        if (!project.hasProperty("sandbox")) excludeTags("sandbox")
    }
}