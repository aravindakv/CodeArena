plugins {
    `kotlin-dsl`
}

// Also declared in build-logic/settings.gradle.kts; repeated here so the plugins resolve even if that file is missing.
repositories {
    gradlePluginPortal()
    mavenCentral()
}

dependencies {
    // Keep the Boot version in step with Versions.kt
    implementation("org.springframework.boot:spring-boot-gradle-plugin:4.0.9")
    implementation("io.spring.gradle:dependency-management-plugin:1.1.7")
}