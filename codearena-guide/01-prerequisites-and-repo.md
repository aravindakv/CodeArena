# 01 · Prerequisites and the Gradle monorepo

You set up the tools once, create the repository, and write the build logic that every Java module shares.

## Step 1: install the tools (Ubuntu or another Linux)

| Tool | Version | Why |
| --- | --- | --- |
| JDK | 25 | Runs Gradle and is the services' target (toolchain) |
| Gradle | 9.1+ (only to create the wrapper once) | Build |
| Docker Engine + Compose plugin | recent | Infrastructure and the code sandbox |
| gVisor (`runsc`) | latest | Kernel isolation for user code (chapter 07) |
| Node.js | 20 or 22 | Web app |
| Python + uv | 3.13, uv latest | Helper scripts and the ingestion worker |
| openssl, curl | any | JWT key, checks |

```bash
# JDK + Gradle (SDKMAN)
curl -s https://get.sdkman.io | bash && source ~/.sdkman/bin/sdkman-init.sh
sdk install java 25.0.1-tem          # any JDK 25 build works
sdk install gradle 9.1.0
# Docker Engine (skip if you already use it)
curl -fsSL https://get.docker.com | sh && sudo usermod -aG docker "$USER"   # log out and back in afterwards
# Node 24 LTS (via nvm); Vite 8 needs Node 22.12 or newer
curl -o- https://raw.githubusercontent.com/nvm-sh/nvm/v0.40.1/install.sh | bash && nvm install 24
# uv (fast Python package manager; scripts/py.sh falls back to a venv without it)
curl -LsSf https://astral.sh/uv/install.sh | sh
```

Check: `java -version`, `docker run --rm hello-world`, `node -v`, `uv --version`.

| Piece | Version | Note |
| --- | --- | --- |
| Gradle / Java | 9.1.0 / 25 | Gradle 9.1 is the first release that runs on, and compiles for, Java 25 |
| Spring Boot | 4.0.x | modular starters (`spring-boot-starter-webmvc`, `spring-boot-starter-security-oauth2-resource-server`), Jackson 3 |
| Spring Cloud | 2025.1.x (Oakwood) | the train for Boot 4.0 |
| Spring Cloud AWS | 4.0.x | documented for Boot 4.0.x; this is why the build is not on Boot 4.1 yet |
| Testcontainers | 2.x (managed by Boot) | modules renamed, e.g. `testcontainers-junit-jupiter` |

## Step 2: create the repository

```bash
mkdir -p ~/Projects/codearena && cd ~/Projects/codearena
git init -b main
touch settings.gradle.kts                  # Gradle only runs tasks inside a build, even `wrapper`
gradle wrapper --gradle-version 9.1.0      # creates ./gradlew and gradle/wrapper/*
rm settings.gradle.kts                     # the real one is written in the next step
```

From here on always run `./gradlew`, never your installed `gradle`: the wrapper pins the version for everyone.

The root build has three jobs: name the project, include the build logic, and list the modules. The Foojay plugin
lets Gradle download JDK 25 for the build if it is not installed.

**File:** `settings.gradle.kts`

```kotlin
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
```

**File:** `build.gradle.kts`

```kotlin
// Root build: everything lives in the convention plugins under build-logic/.
// Useful tasks:
//   ./gradlew build              compile + test every module
//   ./gradlew bootJar            build runnable jars (scripts/services.sh uses these)
tasks.register("printModules") {
    doLast { subprojects.forEach { println(it.path) } }
}
```

**File:** `gradle.properties`

```properties
org.gradle.jvmargs=-Xmx2g -Dfile.encoding=UTF-8
org.gradle.parallel=true
org.gradle.caching=true
org.gradle.configuration-cache=false
```

**File:** `.gitignore`

```text
.gradle/
build/
out/
.idea/
.vscode/
*.iml
.local/
.env
node_modules/
frontend/dist/
__pycache__/
.pytest_cache/
.venv/
*.pyc
```

## Step 3: convention plugins (build-logic)

Instead of copying the same 40 lines into every service, two *precompiled script plugins* hold the shared setup:
`codearena.java-library` (Java 25 toolchain, the Spring Boot / Spring Cloud / Spring Cloud AWS BOMs, JUnit) and
`codearena.service` (adds Spring Boot, actuator, Prometheus metrics and names the jar after the service).
A service's build file then starts with `plugins { id("codearena.service") }`.

**File:** `build-logic/settings.gradle.kts`

```kotlin
rootProject.name = "build-logic"

dependencyResolutionManagement {
    repositories { gradlePluginPortal(); mavenCentral() }
}
```

**File:** `build-logic/build.gradle.kts`

```kotlin
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
```

All versions live in one object. When you start, move each to the latest patch of the same line; the three
Spring lines must stay compatible (Boot 4.0.x, Cloud 2025.1.x, Cloud AWS 4.0.x).

**File:** `build-logic/src/main/kotlin/Versions.kt`

```kotlin
/**
 * One place for every version the build uses (Gradle 9.1, Java 25).
 * The Spring lines must stay compatible:
 *   Spring Boot 4.0.x  <->  Spring Cloud 2025.1.x (Oakwood)  <->  Spring Cloud AWS 4.0.x
 * Spring Cloud AWS documents support for Boot 4.0.x only, which is why this is not on Boot 4.1 yet.
 */
object Versions {
    const val JAVA = 25
    const val SPRING_BOOT = "4.0.9"
    const val SPRING_CLOUD = "2025.1.3"
    const val SPRING_CLOUD_AWS = "4.0.2"
    const val JMUSTACHE = "1.16"
    const val ULID = "5.2.3"
    const val RESILIENCE4J = "2.3.0"
}
```

**File:** `build-logic/src/main/kotlin/codearena.java-library.gradle.kts`

```kotlin
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
```

**File:** `build-logic/src/main/kotlin/codearena.service.gradle.kts`

```kotlin
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
```

## Step 4: environment file and README

Every script and service reads the same settings. Copy the example to `.env` (git ignores `.env`).

**File:** `.env.example`

```bash
# Copy to .env and adjust. scripts/services.sh and the ingestion worker read this file.

# Local infrastructure (all bound to 127.0.0.1 by deploy/compose/docker-compose.yml)
DYNAMO_ENDPOINT=http://localhost:9700
REDIS_HOST=localhost
REDIS_PORT=9701
SQS_ENDPOINT=http://localhost:9702
S3_ENDPOINT=http://localhost:9703
PG_URL=postgresql://codearena:codearena@localhost:9704/codearena
AWS_REGION=ap-south-1
AWS_ACCESS_KEY_ID=local
AWS_SECRET_ACCESS_KEY=local

# Services
AUTH_URL=http://localhost:9081
PROBLEM_URL=http://localhost:9082

# First admin: this email is promoted to ADMIN by `scripts/make-admin.sh`
BOOTSTRAP_ADMIN_EMAIL=admin@codearena.local

# Judge: runsc (gVisor, recommended) or runc (no extra isolation; dev only)
JUDGE_RUNTIME=runsc
JUDGE_CONCURRENCY=4

# AI ingestion: "anthropic" (needs ANTHROPIC_API_KEY) or "mock" (offline, canned problems)
LLM_PROVIDER=mock
ANTHROPIC_API_KEY=
LLM_MODEL=claude-sonnet-5-5
```

**File:** `README.md`

````markdown
# CodeArena

A LeetCode-style practice and coding-fest platform that runs entirely on your machine:
Spring Boot microservices behind a gateway, a React + Monaco web app, a gVisor-sandboxed judge,
a Redis leaderboard refreshed every 5 seconds, per-user stats, and an admin-only AI + RAG pipeline
that writes and verifies new problems.

## Quick start (Linux)

```bash
cp .env.example .env                    # adjust if you like
touch settings.gradle.kts && gradle wrapper --gradle-version 9.1.0    # once, only if ./gradlew is missing (Gradle 9.1, JDK 25)
scripts/up.sh                           # infrastructure in Docker + tables + seed problems
sandbox-images/build-all.sh             # code runner images (one per language)
scripts/services.sh start               # all Java services as local processes
cd frontend && npm install && npm run dev
```

Open http://localhost:5173. Register with the email in `BOOTSTRAP_ADMIN_EMAIL` to get the admin role.
Check the whole stack end to end with `scripts/smoke.sh`.

## Layout

| Path | What |
| --- | --- |
| `services/api-gateway` | Routing, JWT check, roles, Redis rate limits, circuit breakers (port 9080) |
| `services/auth-service` | Register, login, refresh rotation, JWKS, admin role management (9081) |
| `services/problem-service` | Problem catalog, 100-per-page list, starter code, admin review, ingestion runs (9082) |
| `services/submission-service` | Accepts code, queues it, reports verdicts (9083) |
| `services/judge-worker` | Runs code in the sandbox and judges it (no port) |
| `services/contest-service` | Fests, registration, Lua-scored leaderboard, 5 s snapshots (9084) |
| `services/analytics-service` | Per-user stats, streaks, heatmap, admin overview (9085) |
| `libs/common`, `libs/codegen` | Shared events/security/errors; starter code + harness generator |
| `sandbox-images` | One runner image per language |
| `frontend` | React + Vite + Tailwind + Monaco |
| `ingestion` | Python AI + RAG problem pipeline (admin only) |
| `deploy/compose` | Local infrastructure: DynamoDB Local, Redis, ElasticMQ (SQS), S3Mock, pgvector |
| `scripts` | up, services, stop, seed, smoke, ingest |
| `load` | k6 fest load test |

The step-by-step build guide (chapters 00–16) explains every file.
````

```bash
cp .env.example .env
```

## Check

```bash
./gradlew help          # BUILD SUCCESSFUL; the first run downloads Gradle 9.1 and the plugins
```

Until you create the modules (chapters 03 to 11), the build prints `CodeArena: not created yet, skipped: ...`
and carries on. Each chapter's module is picked up automatically as soon as its folder exists.

If you see `Deprecated Gradle Property 'kotlin.incremental.useClasspathSnapshot'`, that line comes from your personal
`~/.gradle/gradle.properties` (often left there by Android projects), not from this repo. It is harmless; delete the
line to silence it.
