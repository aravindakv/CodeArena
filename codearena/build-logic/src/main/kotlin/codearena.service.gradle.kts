// Convention for every runnable service: the library convention + Spring Boot + actuator.
plugins {
    id("codearena.java-library")
    id("org.springframework.boot")
}

dependencies {
    implementation(project(":libs:common"))
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("io.micrometer:micrometer-registry-prometheus")
}

// The fat jar is named <service>.jar so scripts/services.sh can find it.
tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    archiveFileName.set("${project.name}.jar")
}

// Plain jar is not needed for services.
tasks.named<Jar>("jar") { enabled = false }