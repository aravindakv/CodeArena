rootProject.name = "codearena"

pluginManagement {
    includeBuild("build-logic")
    repositories { gradlePluginPortal(); mavenCentral() }
}

plugins {
    // Downloads JDK 25 for the build if it is not installed.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositories { mavenCentral() }
}

// Every module of the platform. While you build chapter by chapter, only the modules whose folder already
// exists are included (Gradle 9 fails on a module without a folder), so the build works after every chapter.
val modules = listOf(
    ":libs:common",
    ":libs:codegen",
    ":services:auth-service",
    ":services:api-gateway",
    ":services:problem-service",
    ":services:submission-service",
    ":services:judge-worker",
    ":services:contest-service",
    ":services:analytics-service",
)
val (present, missing) = modules.partition { file(it.removePrefix(":").replace(':', '/')).isDirectory }
present.forEach { include(it) }
if (missing.isNotEmpty()) println("CodeArena: not created yet, skipped: ${missing.joinToString()}")