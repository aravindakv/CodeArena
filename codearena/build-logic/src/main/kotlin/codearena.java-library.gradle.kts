// Convention for every Java module: toolchain, BOMs, tests.
plugins {
    `java-library`
    id("io.spring.dependency-management")
}

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(Versions.JAVA)) }
}

dependencyManagement {
    imports {
        mavenBom("org.springframework.boot:spring-boot-dependencies:${Versions.SPRING_BOOT}")
        mavenBom("org.springframework.cloud:spring-cloud-dependencies:${Versions.SPRING_CLOUD}")
        mavenBom("io.awspring.cloud:spring-cloud-aws-dependencies:${Versions.SPRING_CLOUD_AWS}")
    }
}

dependencies {
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.add("-parameters")   // Spring needs parameter names for @PathVariable etc.
    options.encoding = "UTF-8"
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}