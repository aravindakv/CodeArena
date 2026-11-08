# 09 · Judge worker: run, judge, publish, and survive malicious code

The judge pulls a submission from SQS, fetches the code and the hidden tests, runs them in the sandbox (chapter 07),
decides the verdict, writes it onto the submission, and publishes a `VerdictReady` event for the leaderboard and
analytics.

It runs as a plain process on a host with Docker, not inside a container: it needs to start containers. That makes
the judge host privileged, which is why on AWS judge hosts run nothing else and are recycled every few hours.

## Verdicts

| Verdict | Decided when |
| --- | --- |
| ACCEPTED | every result matches and peak memory is within the limit |
| WRONG_ANSWER | the first result that differs; its input, expected and actual output are shown |
| TIME_LIMIT_EXCEEDED | killed near the time limit, or the outer wall clock fired |
| MEMORY_LIMIT_EXCEEDED | killed early (OOM), an out-of-memory error in stderr, or peak memory above the limit |
| RUNTIME_ERROR | exited non-zero before finishing; stderr is shown |
| COMPILATION_ERROR | the compiler (or a syntax check) failed; not counted against you in fests |
| OUTPUT_LIMIT_EXCEEDED | printed 64 KB or more |
| SYSTEM_ERROR | the judge itself failed three times; the submission is released instead of hanging |

## Fault tolerance

| Failure | What happens |
| --- | --- |
| Judge crashes mid-run | the SQS message was not deleted, so it reappears after 60 s and another worker takes it |
| Same message delivered twice | the verdict write is conditional on `status = PENDING`, and the leaderboard and stats ignore duplicate submission ids |
| A submission that crashes the judge every time | on the third receive the judge records SYSTEM_ERROR and acknowledges it; ElasticMQ also moves it to the DLQ |
| Docker or an image missing | not blamed on the user: the message is retried and the log says what is wrong |
| Sandbox left behind after a kill | named `ca-*`; `scripts/stop.sh wipe` removes strays |

**File:** `services/judge-worker/build.gradle.kts`

```kotlin
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
```

**File:** `services/judge-worker/src/main/resources/application.yml`

```yaml
spring:
  application:
    name: judge-worker
  config:
    import: classpath:codearena-common.yml
  main:
    web-application-type: none
    keep-alive: true            # no web server; keep the JVM up for the SQS listeners
  cloud:
    aws:
      sqs:
        listener:
          max-concurrent-messages: ${JUDGE_CONCURRENCY:4}
          max-messages-per-poll: ${JUDGE_CONCURRENCY:4}

codearena:
  judge:
    runtime: ${JUDGE_RUNTIME:runsc}                 # runsc = gVisor (recommended); runc = plain Docker (dev only)
    work-root: ${JUDGE_WORK_ROOT:/tmp/codearena-judge}
    problem-url: ${PROBLEM_URL:http://localhost:9082}
    tests-bucket: codearena-tests
    submissions-bucket: codearena-submissions
    max-output-bytes: 65536
    verdict-queues: verdict-events,contest-verdicts  # fan-out (EventBridge on AWS)
```

**File:** `services/judge-worker/src/main/java/dev/codearena/judge/JudgeApplication.java`

```java
package dev.codearena.judge;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class JudgeApplication {
    public static void main(String[] args) {
        SpringApplication.run(JudgeApplication.class, args);
    }
}
```

**File:** `services/judge-worker/src/main/java/dev/codearena/judge/JudgeProps.java`

```java
package dev.codearena.judge;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties("codearena.judge")
public record JudgeProps(String runtime, String workRoot, String problemUrl, String testsBucket,
                         String submissionsBucket, int maxOutputBytes, List<String> verdictQueues) {
}
```

**File:** `services/judge-worker/src/main/java/dev/codearena/judge/LangRuntime.java`

```java
package dev.codearena.judge;

import dev.codearena.codegen.Lang;

/**
 * Container settings per language. Compiled languages need an executable /tmp and extra memory for the
 * compiler; their run-time memory limit is then enforced from the measured peak (maxrss).
 *
 * @param tmpfs          mount options for /tmp inside the container
 * @param extraMemoryMb  added to the problem's memory limit for the container (compiler, JVM)
 * @param startupMs      added to the problem's time limit (interpreter or JVM start-up)
 * @param compileBudgetMs worst-case compile time, used for the outer wall-clock limit
 */
record LangRuntime(String tmpfs, int extraMemoryMb, int startupMs, int compileBudgetMs) {

    static LangRuntime of(Lang lang) {
        return switch (lang) {
            case PYTHON -> new LangRuntime("rw,noexec,nosuid,size=64m", 0, 200, 2_000);
            case JAVASCRIPT -> new LangRuntime("rw,noexec,nosuid,size=64m", 64, 300, 2_000);
            case JAVA -> new LangRuntime("rw,noexec,nosuid,size=128m", 320, 800, 20_000);
            case CPP -> new LangRuntime("rw,exec,nosuid,size=128m", 512, 0, 30_000);
            case GO -> new LangRuntime("rw,exec,nosuid,size=512m", 768, 0, 30_000);
        };
    }
}
```

**File:** `services/judge-worker/src/main/java/dev/codearena/judge/RunResult.java`

```java
package dev.codearena.judge;

import java.util.List;

/**
 * What came back from one sandbox run.
 *
 * @param compileError compiler output if compilation failed, else null
 * @param finished     false when the container was killed from outside (wall clock) or died before writing /out/meta
 * @param exitCode     exit status of the program (137 = killed by SIGKILL: time or memory)
 * @param elapsedMs    run time of the program over all inputs
 * @param maxRssKb     peak memory of the program
 * @param results      one JSON line per input the program completed
 * @param stdout       the program's own prints (capped), for debugging
 * @param stderr       first part of stderr (stack traces)
 * @param containerExit exit status of `docker run` itself
 */
record RunResult(String compileError, boolean finished, boolean wallTimeout, int exitCode, long elapsedMs,
                 long maxRssKb, List<String> results, String stdout, long stdoutBytes, String stderr,
                 int containerExit) {
}
```

**File:** `services/judge-worker/src/main/java/dev/codearena/judge/Sandbox.java`

```java
package dev.codearena.judge;

import dev.codearena.codegen.Lang;
import dev.codearena.common.events.Limits;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Runs untrusted code in a throw-away container. Every limit that protects the host is set here:
 * no network, read-only root, capped tmpfs, memory and swap, CPU, process count, open files, file size,
 * no capabilities, no privilege escalation, unprivileged user, gVisor kernel (runsc), wall-clock kill.
 */
@Component
class Sandbox {
    private static final Logger log = LoggerFactory.getLogger(Sandbox.class);
    private final JudgeProps props;

    Sandbox(JudgeProps props) {
        this.props = props;
        if (!"runsc".equals(props.runtime()))
            log.warn("JUDGE_RUNTIME={} - user code runs WITHOUT gVisor. Acceptable on a dev machine only.", props.runtime());
    }

    RunResult run(Lang lang, String userCode, String harness, List<String> inputs, Limits limits) throws IOException, InterruptedException {
        LangRuntime rt = LangRuntime.of(lang);
        Path root = Files.createDirectories(Path.of(props.workRoot()));
        Path job = Files.createTempDirectory(root, "job-");
        Path work = Files.createDirectories(job.resolve("work"));
        Path out = Files.createDirectories(job.resolve("out"));
        String name = "ca-" + UUID.randomUUID();
        try {
            Files.writeString(work.resolve(lang.userFile), userCode, StandardCharsets.UTF_8);
            Files.writeString(work.resolve(lang.harnessFile), harness, StandardCharsets.UTF_8);
            Files.write(work.resolve("inputs.jsonl"), inputs, StandardCharsets.UTF_8);
            Files.setPosixFilePermissions(job, PosixFilePermissions.fromString("rwxr-xr-x"));
            Files.setPosixFilePermissions(work, PosixFilePermissions.fromString("rwxr-xr-x"));
            try (Stream<Path> files = Files.list(work)) {
                for (Path f : files.toList()) Files.setPosixFilePermissions(f, PosixFilePermissions.fromString("rw-r--r--"));
            }
            Files.setPosixFilePermissions(out, PosixFilePermissions.fromString("rwxrwxrwx"));   // container user 65534 writes here

            int timeMs = limits.timeMs() + rt.startupMs();
            int memoryMb = limits.memoryMb() + rt.extraMemoryMb();
            List<String> cmd = new ArrayList<>(List.of("docker", "run", "--rm", "--name", name,
                    "--runtime=" + props.runtime(),
                    "--network=none",
                    "--read-only",
                    "--tmpfs", "/tmp:" + rt.tmpfs(),
                    "--memory=" + memoryMb + "m", "--memory-swap=" + memoryMb + "m",
                    "--cpus=1", "--pids-limit=128",
                    "--cap-drop=ALL", "--security-opt=no-new-privileges",
                    "--user", "65534:65534",
                    "--ulimit", "nofile=64:64", "--ulimit", "fsize=10485760:10485760",
                    "-e", "TIME_LIMIT_MS=" + timeMs,
                    "-e", "MAX_OUT_BYTES=" + props.maxOutputBytes(),
                    "-e", "MEMORY_MB=" + limits.memoryMb(),
                    "-v", work.toAbsolutePath() + ":/work:ro",
                    "-v", out.toAbsolutePath() + ":/out:rw",
                    lang.image()));

            Process p = new ProcessBuilder(cmd).redirectErrorStream(true)
                    .redirectOutput(job.resolve("docker.log").toFile()).start();
            long wallMs = timeMs + rt.compileBudgetMs() + 10_000L;
            boolean wallTimeout = !p.waitFor(wallMs, TimeUnit.MILLISECONDS);
            if (wallTimeout) {
                log.warn("wall-clock timeout after {} ms, killing {}", wallMs, name);
                new ProcessBuilder("docker", "kill", name).start().waitFor(10, TimeUnit.SECONDS);
                p.waitFor(10, TimeUnit.SECONDS);
            }
            int containerExit = p.isAlive() ? -1 : p.exitValue();
            if (containerExit == 125 || containerExit == 126 || containerExit == 127) {
                // docker itself failed (image missing, runtime not installed...). Not the user's fault.
                throw new IllegalStateException("docker run failed (" + containerExit + "): " + head(job.resolve("docker.log"), 2000));
            }
            return read(out, wallTimeout, containerExit);
        } finally {
            deleteQuietly(job);
        }
    }

    private RunResult read(Path out, boolean wallTimeout, int containerExit) throws IOException {
        String meta = Files.exists(out.resolve("meta")) ? Files.readString(out.resolve("meta")).trim() : "";
        String compileErr = meta.startsWith("COMPILE") ? head(out.resolve("compile.err"), 4000) : null;
        boolean finished = meta.startsWith("RUN");
        int exit = -1;
        long elapsed = 0;
        if (finished) {
            String[] parts = meta.split("\\s+");
            exit = Integer.parseInt(parts[1]);
            elapsed = Long.parseLong(parts[2]);
        }
        long rss = 0;
        try {
            String r = Files.exists(out.resolve("maxrss_kb")) ? Files.readString(out.resolve("maxrss_kb")).trim() : "";
            // GNU time may print "Command terminated by signal 9" before the number: take the last line.
            String[] lines = r.split("\n");
            rss = lines.length == 0 || lines[lines.length - 1].isBlank() ? 0 : Long.parseLong(lines[lines.length - 1].trim());
        } catch (NumberFormatException ignored) {
            // leave 0
        }
        List<String> results = Files.exists(out.resolve("results.jsonl"))
                ? Files.readAllLines(out.resolve("results.jsonl")).stream().filter(l -> !l.isBlank()).toList()
                : List.of();
        long stdoutBytes = Files.exists(out.resolve("stdout")) ? Files.size(out.resolve("stdout")) : 0;
        return new RunResult(compileErr, finished, wallTimeout, exit, elapsed, rss, results,
                head(out.resolve("stdout"), 4000), stdoutBytes, head(out.resolve("stderr"), 4000), containerExit);
    }

    static String head(Path p, int maxChars) throws IOException {
        if (!Files.exists(p)) return "";
        byte[] b = Files.readAllBytes(p);
        String s = new String(b, 0, Math.min(b.length, maxChars * 2), StandardCharsets.UTF_8);
        return s.length() > maxChars ? s.substring(0, maxChars) + "\n... (truncated)" : s;
    }

    private static void deleteQuietly(Path dir) {
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(f -> f.toFile().delete());
        } catch (IOException e) {
            log.warn("could not delete {}: {}", dir, e.getMessage());
        }
    }
}
```

**File:** `services/judge-worker/src/main/java/dev/codearena/judge/Comparators.java`

```java
package dev.codearena.judge;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Output comparison modes (set per problem in signature.compare):
 *   EXACT            the JSON text must match exactly (after trimming)
 *   JSON             parsed values must be equal; 2 and 2.0 count as equal
 *   UNORDERED_ARRAY  arrays with the same elements in any order (one level deep)
 *   FLOAT_1E6        numbers (or arrays of numbers) within 1e-6 absolute or relative error
 */
final class Comparators {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private Comparators() {}

    static boolean equal(String mode, String expected, String actual) {
        if (actual == null) return false;
        String e = expected.trim(), a = actual.trim();
        try {
            return switch (mode == null ? "JSON" : mode) {
                case "EXACT" -> e.equals(a);
                case "UNORDERED_ARRAY" -> unordered(JSON.readTree(e), JSON.readTree(a));
                case "FLOAT_1E6" -> close(JSON.readTree(e), JSON.readTree(a));
                default -> same(JSON.readTree(e), JSON.readTree(a));
            };
        } catch (Exception ex) {
            return false;   // the program printed something that is not JSON
        }
    }

    static boolean same(JsonNode e, JsonNode a) {
        if (e.isNumber() && a.isNumber()) return e.decimalValue().compareTo(a.decimalValue()) == 0;
        if (e.isArray() && a.isArray()) {
            if (e.size() != a.size()) return false;
            for (int i = 0; i < e.size(); i++) if (!same(e.get(i), a.get(i))) return false;
            return true;
        }
        if (e.isObject() && a.isObject()) {
            if (e.size() != a.size()) return false;
            for (Map.Entry<String, JsonNode> f : e.properties()) {
                if (!a.has(f.getKey()) || !same(f.getValue(), a.get(f.getKey()))) return false;
            }
            return true;
        }
        return e.equals(a);
    }

    static boolean unordered(JsonNode e, JsonNode a) {
        if (!e.isArray() || !a.isArray() || e.size() != a.size()) return same(e, a);
        return same(sorted(e), sorted(a));
    }

    private static JsonNode sorted(JsonNode arr) {
        List<JsonNode> items = new ArrayList<>();
        for (int i = 0; i < arr.size(); i++) items.add(arr.get(i));
        items.sort(Comparator.comparing(JsonNode::toString));
        return JSON.valueToTree(items);
    }

    static boolean close(JsonNode e, JsonNode a) {
        if (e.isNumber() && a.isNumber()) {
            double x = e.doubleValue(), y = a.doubleValue();
            double diff = Math.abs(x - y);
            return diff <= 1e-6 || diff <= 1e-6 * Math.max(Math.abs(x), Math.abs(y));
        }
        if (e.isArray() && a.isArray()) {
            if (e.size() != a.size()) return false;
            for (int i = 0; i < e.size(); i++) if (!close(e.get(i), a.get(i))) return false;
            return true;
        }
        return same(e, a);
    }
}
```

**File:** `services/judge-worker/src/main/java/dev/codearena/judge/Verdict.java`

```java
package dev.codearena.judge;

/**
 * Outcome of judging. failedInput/expected/actual are only filled for visible information:
 * for hidden tests we show the input and expected output of the first failing case, like most judges do.
 */
record Verdict(String status, int passed, int total, long runtimeMs, long memoryKb, String message,
               String failedInput, String expected, String actual, String stdout, String customOutput) {

    static Verdict of(String status, int passed, int total, String message) {
        return new Verdict(status, passed, total, 0, 0, message, null, null, null, null, null);
    }

    Verdict withDebug(String stdout, String customOutput) {
        return new Verdict(status, passed, total, runtimeMs, memoryKb, message, failedInput, expected, actual,
                stdout, customOutput);
    }

    boolean accepted() {
        return "ACCEPTED".equals(status);
    }
}
```

**File:** `services/judge-worker/src/main/java/dev/codearena/judge/Judge.java`

```java
package dev.codearena.judge;

import java.util.List;

/** Turns a sandbox run into a verdict. Pure logic, unit-tested without Docker. */
final class Judge {
    private Judge() {}

    /**
     * @param inputs   the judged inputs (custom input, if any, is NOT in this list)
     * @param expected expected outputs, same order
     */
    static Verdict decide(RunResult r, List<String> inputs, List<String> expected, String compare,
                          int timeLimitMs, int memoryLimitMb, int maxOutputBytes) {
        int total = expected.size();
        if (r.compileError() != null)
            return Verdict.of("COMPILATION_ERROR", 0, total, r.compileError());
        if (!r.finished()) {
            if (r.wallTimeout()) return Verdict.of("TIME_LIMIT_EXCEEDED", 0, total, "time limit exceeded");
            if (r.containerExit() == 137) return Verdict.of("MEMORY_LIMIT_EXCEEDED", 0, total, "memory limit exceeded");
            throw new IllegalStateException("sandbox ended without a result (exit " + r.containerExit() + ")");
        }

        List<String> got = r.results();
        int n = Math.min(got.size(), total);
        for (int i = 0; i < n; i++) {
            if (!Comparators.equal(compare, expected.get(i), got.get(i))) {
                return new Verdict("WRONG_ANSWER", i, total, r.elapsedMs(), r.maxRssKb(),
                        "wrong answer on test " + (i + 1), inputs.get(i), expected.get(i), truncate(got.get(i)), null, null);
            }
        }
        if (got.size() < total) {
            String failing = inputs.get(got.size());
            if (r.stdoutBytes() >= maxOutputBytes)
                return failed("OUTPUT_LIMIT_EXCEEDED", got.size(), total, r, "too much output", failing);
            if (r.stderr().contains("OutOfMemoryError") || r.stderr().contains("MemoryError")
                    || r.stderr().contains("std::bad_alloc") || r.stderr().contains("heap out of memory"))
                return failed("MEMORY_LIMIT_EXCEEDED", got.size(), total, r, "memory limit exceeded", failing);
            if (r.exitCode() == 137 || r.exitCode() == 124) {
                boolean outOfTime = r.elapsedMs() >= timeLimitMs * 0.95;
                return failed(outOfTime ? "TIME_LIMIT_EXCEEDED" : "MEMORY_LIMIT_EXCEEDED", got.size(), total, r,
                        outOfTime ? "time limit exceeded" : "memory limit exceeded", failing);
            }
            return failed("RUNTIME_ERROR", got.size(), total, r,
                    r.stderr().isBlank() ? "program exited with code " + r.exitCode() : r.stderr(), failing);
        }
        if (r.maxRssKb() > memoryLimitMb * 1024L)
            return failed("MEMORY_LIMIT_EXCEEDED", total, total, r, "memory limit exceeded", null);
        return new Verdict("ACCEPTED", total, total, r.elapsedMs(), r.maxRssKb(), "accepted", null, null, null, null, null);
    }

    private static Verdict failed(String status, int passed, int total, RunResult r, String message, String input) {
        return new Verdict(status, passed, total, r.elapsedMs(), r.maxRssKb(), message, input, null, null, null, null);
    }

    static String truncate(String s) {
        return s == null || s.length() <= 1000 ? s : s.substring(0, 1000) + "...";
    }
}
```

**File:** `services/judge-worker/src/main/java/dev/codearena/judge/ProblemMetaClient.java`

```java
package dev.codearena.judge;

import dev.codearena.codegen.Signature;
import dev.codearena.common.events.Limits;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Judge metadata (signature, visible examples) from problem-service, cached in memory for 5 minutes. */
@Component
class ProblemMetaClient {
    private final RestClient http;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    ProblemMetaClient(JudgeProps props) {
        this.http = RestClient.create(props.problemUrl());
    }

    record JudgeMeta(String slug, String status, int testsVersion, Limits limits, Signature signature,
                     String difficulty, List<String> tags, List<String> visibleInputs, List<String> visibleOutputs) {}

    private record Cached(JudgeMeta meta, long at) {}

    JudgeMeta get(String slug) {
        Cached c = cache.get(slug);
        if (c != null && System.currentTimeMillis() - c.at() < 300_000) return c.meta();
        JudgeMeta meta = http.get().uri("/internal/problems/{slug}/judge-meta", slug).retrieve().body(JudgeMeta.class);
        cache.put(slug, new Cached(meta, System.currentTimeMillis()));
        return meta;
    }
}
```

**File:** `services/judge-worker/src/main/java/dev/codearena/judge/TestCases.java`

```java
package dev.codearena.judge;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.s3.S3Client;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Hidden tests from S3, cached on the judge host's disk by slug + version (versions are immutable,
 * so the cache never needs invalidating). Inputs and expected outputs are split here: only inputs
 * are ever mounted into the sandbox.
 */
@Component
class TestCases {
    private final S3Client s3;
    private final JudgeProps props;
    private final JsonMapper json = JsonMapper.builder().build();

    TestCases(S3Client s3, JudgeProps props) {
        this.s3 = s3;
        this.props = props;
    }

    record Cases(List<String> inputs, List<String> expected) {}

    Cases load(String slug, int version) throws IOException {
        Path cached = Path.of(props.workRoot(), "tests-cache", slug, "v" + version + ".jsonl");
        if (!Files.exists(cached)) {
            Files.createDirectories(cached.getParent());
            Path tmp = Files.createTempFile(cached.getParent(), "dl-", ".part");
            try (var in = s3.getObject(b -> b.bucket(props.testsBucket()).key(slug + "/v" + version + "/cases.jsonl"))) {
                Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
            }
            Files.move(tmp, cached, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        }
        List<String> inputs = new ArrayList<>(), expected = new ArrayList<>();
        for (String line : Files.readAllLines(cached, StandardCharsets.UTF_8)) {
            if (line.isBlank()) continue;
            JsonNode n = json.readTree(line);
            inputs.add(json.writeValueAsString(n.get("input")));
            expected.add(json.writeValueAsString(n.get("output")));
        }
        return new Cases(inputs, expected);
    }
}
```

**File:** `services/judge-worker/src/main/java/dev/codearena/judge/SubmissionStore.java`

```java
package dev.codearena.judge;

import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static dev.codearena.common.aws.Ddb.*;

/** Reads submitted source from S3 and writes verdicts onto the Submissions item. */
@Component
class SubmissionStore {
    private final DynamoDbClient ddb;
    private final S3Client s3;
    private final JudgeProps props;

    SubmissionStore(DynamoDbClient ddb, S3Client s3, JudgeProps props) {
        this.ddb = ddb;
        this.s3 = s3;
        this.props = props;
    }

    String source(String submissionId) {
        ResponseBytes<GetObjectResponse> b = s3.getObjectAsBytes(r -> r.bucket(props.submissionsBucket()).key("src/" + submissionId));
        return b.asString(StandardCharsets.UTF_8);
    }

    boolean isPending(String userId, String submissionId) {
        var item = ddb.getItem(r -> r.tableName("Submissions").key(key("USER#" + userId, "SUB#" + submissionId))
                .consistentRead(true)).item();
        return has(item) && "PENDING".equals(str(item, "status"));
    }

    /**
     * Writes the verdict only if the submission is still PENDING. Returns false when another worker already
     * finished it (SQS delivers at least once), so the verdict event is published exactly once per submission.
     */
    boolean finish(String userId, String submissionId, Verdict v) {
        Map<String, AttributeValue> values = new HashMap<>();
        values.put(":st", s(v.status()));
        values.put(":rt", n(v.runtimeMs()));
        values.put(":mem", n(v.memoryKb()));
        values.put(":p", n(v.passed()));
        values.put(":t", n(v.total()));
        values.put(":msg", s(nz(v.message())));
        values.put(":fi", s(nz(v.failedInput())));
        values.put(":ex", s(nz(v.expected())));
        values.put(":ac", s(nz(v.actual())));
        values.put(":out", s(nz(v.stdout())));
        values.put(":co", s(nz(v.customOutput())));
        values.put(":at", s(Instant.now().toString()));
        values.put(":pending", s("PENDING"));
        try {
            ddb.updateItem(r -> r.tableName("Submissions").key(key("USER#" + userId, "SUB#" + submissionId))
                    .updateExpression("SET #st = :st, runtimeMs = :rt, memoryKb = :mem, passed = :p, #total = :t, "
                            + "message = :msg, failedInput = :fi, expected = :ex, actual = :ac, stdout = :out, "
                            + "customOutput = :co, judgedAt = :at")
                    .conditionExpression("#st = :pending")
                    .expressionAttributeNames(Map.of("#st", "status", "#total", "total"))
                    .expressionAttributeValues(values));
            return true;
        } catch (ConditionalCheckFailedException e) {
            return false;
        }
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
```

**File:** `services/judge-worker/src/main/java/dev/codearena/judge/VerdictPublisher.java`

```java
package dev.codearena.judge;

import dev.codearena.common.events.VerdictReady;
import io.awspring.cloud.sqs.operations.SqsTemplate;
import org.springframework.stereotype.Component;

/**
 * Fan-out of verdict events. Locally: one SQS message per subscriber queue.
 * On AWS replace the body with a single EventBridge PutEvents call; subscribers are then EventBridge rules.
 */
@Component
class VerdictPublisher {
    private final SqsTemplate sqs;
    private final JudgeProps props;

    VerdictPublisher(SqsTemplate sqs, JudgeProps props) {
        this.sqs = sqs;
        this.props = props;
    }

    void publish(VerdictReady event) {
        for (String queue : props.verdictQueues()) sqs.send(queue, event);
    }
}
```

**File:** `services/judge-worker/src/main/java/dev/codearena/judge/JudgeService.java`

```java
package dev.codearena.judge;

import dev.codearena.codegen.CodeGenerator;
import dev.codearena.codegen.Lang;
import dev.codearena.common.events.Limits;
import dev.codearena.common.events.SubmissionCreated;
import dev.codearena.common.events.VerdictReady;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
class JudgeService {
    private static final Logger log = LoggerFactory.getLogger(JudgeService.class);

    private final Sandbox sandbox;
    private final TestCases tests;
    private final ProblemMetaClient problems;
    private final SubmissionStore store;
    private final VerdictPublisher publisher;
    private final JudgeProps props;
    private final CodeGenerator codegen = new CodeGenerator();

    JudgeService(Sandbox sandbox, TestCases tests, ProblemMetaClient problems, SubmissionStore store,
                 VerdictPublisher publisher, JudgeProps props) {
        this.sandbox = sandbox;
        this.tests = tests;
        this.problems = problems;
        this.store = store;
        this.publisher = publisher;
        this.props = props;
    }

    void judge(SubmissionCreated s) throws Exception {
        if (!store.isPending(s.userId(), s.submissionId())) {
            log.info("{} already judged, skipping duplicate delivery", s.submissionId());
            return;
        }
        long t0 = System.currentTimeMillis();
        Lang lang = Lang.of(s.lang());
        var meta = problems.get(s.slug());
        Limits limits = s.limits() != null ? s.limits() : meta.limits();

        List<String> inputs, expected;
        if ("RUN".equals(s.mode())) {
            inputs = new ArrayList<>(meta.visibleInputs());
            expected = meta.visibleOutputs();
        } else {
            var cases = tests.load(s.slug(), s.testsVersion());
            inputs = new ArrayList<>(cases.inputs());
            expected = cases.expected();
        }
        List<String> judged = List.copyOf(inputs);
        boolean custom = s.customInput() != null && !s.customInput().isBlank();
        if (custom) inputs.add(s.customInput().trim());

        String code = store.source(s.submissionId());
        String harness = codegen.harness(lang, meta.signature());
        RunResult run = sandbox.run(lang, code, harness, inputs, limits);

        Verdict v = Judge.decide(run, judged, expected, meta.signature().compare(),
                limits.timeMs() + LangRuntime.of(lang).startupMs(), limits.memoryMb(), props.maxOutputBytes());
        String customOutput = custom && run.results().size() > judged.size() ? run.results().get(judged.size()) : null;
        v = v.withDebug(run.stdout(), customOutput);

        if (store.finish(s.userId(), s.submissionId(), v) && "SUBMIT".equals(s.mode())) {
            long minutes = s.contestStartMs() > 0 ? Math.max(0, (s.submittedAtMs() - s.contestStartMs()) / 60_000) : 0;
            publisher.publish(new VerdictReady(s.submissionId(), s.userId(), s.slug(), s.lang(), s.mode(),
                    s.contestId(), v.status(), v.runtimeMs(), v.memoryKb(), v.passed(), v.total(),
                    s.difficulty(), s.tags(), minutes, s.submittedAtMs()));
        }
        log.info("{} {} {} -> {} ({}/{} passed, {} ms run, {} ms total)", s.submissionId(), s.slug(), s.lang(),
                v.status(), v.passed(), v.total(), v.runtimeMs(), System.currentTimeMillis() - t0);
    }

    /** Called after repeated failures: never leave a user waiting on PENDING forever. */
    void giveUp(SubmissionCreated s, Exception cause) {
        Verdict v = Verdict.of("SYSTEM_ERROR", 0, 0, "the judge could not run this submission; please retry");
        store.finish(s.userId(), s.submissionId(), v);
        log.error("{} gave up after repeated failures", s.submissionId(), cause);
    }
}
```

**File:** `services/judge-worker/src/main/java/dev/codearena/judge/JudgeListener.java`

```java
package dev.codearena.judge;

import dev.codearena.common.events.Queues;
import dev.codearena.common.events.SubmissionCreated;
import io.awspring.cloud.sqs.annotation.SqsListener;
import io.awspring.cloud.sqs.listener.SqsHeaders;
import org.springframework.messaging.Message;
import org.springframework.stereotype.Component;

/**
 * Returning normally deletes the message. Throwing leaves it on the queue; SQS redelivers it after the
 * visibility timeout. On the third attempt we record SYSTEM_ERROR and stop, so a poison submission can
 * neither block the queue nor keep crashing workers. (elasticmq.conf also moves it to a DLQ after 3 receives.)
 */
@Component
class JudgeListener {
    private static final int MAX_ATTEMPTS = 3;
    private final JudgeService judge;

    JudgeListener(JudgeService judge) {
        this.judge = judge;
    }

    @SqsListener(Queues.CONTEST_SUBMISSIONS)
    void contest(Message<SubmissionCreated> message) throws Exception {
        handle(message);
    }

    @SqsListener(Queues.PRACTICE_SUBMISSIONS)
    void practice(Message<SubmissionCreated> message) throws Exception {
        handle(message);
    }

    private void handle(Message<SubmissionCreated> message) throws Exception {
        try {
            judge.judge(message.getPayload());
        } catch (Exception e) {
            if (attempt(message) >= MAX_ATTEMPTS) judge.giveUp(message.getPayload(), e);
            else throw e;
        }
    }

    private static int attempt(Message<?> message) {
        Object v = message.getHeaders().get(SqsHeaders.MessageSystemAttributes.SQS_APPROXIMATE_RECEIVE_COUNT);
        try {
            return v == null ? 1 : Integer.parseInt(v.toString());
        } catch (NumberFormatException e) {
            return 1;
        }
    }
}
```

## Tests

Pure judging logic, no Docker:

**File:** `services/judge-worker/src/test/java/dev/codearena/judge/ComparatorsTest.java`

```java
package dev.codearena.judge;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ComparatorsTest {

    @Test
    void jsonModeTreatsIntAndDoubleAsEqualButNotDifferentValues() {
        assertThat(Comparators.equal("JSON", "[1,2]", "[1.0, 2]")).isTrue();
        assertThat(Comparators.equal("JSON", "[1,2]", "[2,1]")).isFalse();
        assertThat(Comparators.equal("JSON", "true", "true")).isTrue();
        assertThat(Comparators.equal("JSON", "\"abc\"", "\"abd\"")).isFalse();
    }

    @Test
    void unorderedIgnoresOrderOnly() {
        assertThat(Comparators.equal("UNORDERED_ARRAY", "[[1,2],[3]]", "[[3],[1,2]]")).isTrue();
        assertThat(Comparators.equal("UNORDERED_ARRAY", "[1,2,2]", "[2,1,1]")).isFalse();
    }

    @Test
    void floatModeAllowsSmallErrors() {
        assertThat(Comparators.equal("FLOAT_1E6", "0.333333333", "0.3333334")).isTrue();
        assertThat(Comparators.equal("FLOAT_1E6", "1000000", "1000000.5")).isTrue();     // relative
        assertThat(Comparators.equal("FLOAT_1E6", "0.5", "0.51")).isFalse();
    }

    @Test
    void garbageOutputIsWrongNotACrash() {
        assertThat(Comparators.equal("JSON", "[1]", "Traceback (most recent call last)")).isFalse();
        assertThat(Comparators.equal("JSON", "[1]", null)).isFalse();
    }
}
```

**File:** `services/judge-worker/src/test/java/dev/codearena/judge/JudgeTest.java`

```java
package dev.codearena.judge;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class JudgeTest {
    private final List<String> inputs = List.of("{\"a\":1}", "{\"a\":2}", "{\"a\":3}");
    private final List<String> expected = List.of("1", "4", "9");

    private RunResult run(int exit, long ms, long kb, List<String> results, String stderr) {
        return new RunResult(null, true, false, exit, ms, kb, results, "", 0, stderr, 0);
    }

    private Verdict decide(RunResult r) {
        return Judge.decide(r, inputs, expected, "JSON", 2000, 256, 65536);
    }

    @Test
    void acceptsWhenAllMatch() {
        var v = decide(run(0, 120, 9000, List.of("1", "4", "9"), ""));
        assertThat(v.status()).isEqualTo("ACCEPTED");
        assertThat(v.passed()).isEqualTo(3);
    }

    @Test
    void firstMismatchIsWrongAnswerWithItsInput() {
        var v = decide(run(0, 120, 9000, List.of("1", "5", "9"), ""));
        assertThat(v.status()).isEqualTo("WRONG_ANSWER");
        assertThat(v.passed()).isEqualTo(1);
        assertThat(v.failedInput()).isEqualTo("{\"a\":2}");
        assertThat(v.expected()).isEqualTo("4");
        assertThat(v.actual()).isEqualTo("5");
    }

    @Test
    void killedNearTheLimitIsTimeLimit() {
        assertThat(decide(run(137, 2000, 9000, List.of("1"), "")).status()).isEqualTo("TIME_LIMIT_EXCEEDED");
    }

    @Test
    void killedEarlyIsMemoryLimit() {
        assertThat(decide(run(137, 300, 250_000, List.of(), "")).status()).isEqualTo("MEMORY_LIMIT_EXCEEDED");
    }

    @Test
    void crashIsRuntimeErrorWithStderr() {
        var v = decide(run(1, 50, 9000, List.of("1"), "ZeroDivisionError: division by zero"));
        assertThat(v.status()).isEqualTo("RUNTIME_ERROR");
        assertThat(v.message()).contains("ZeroDivisionError");
        assertThat(v.passed()).isEqualTo(1);
    }

    @Test
    void javaOutOfMemoryIsMemoryLimit() {
        var v = decide(run(1, 50, 9000, List.of(), "Exception in thread \"main\" java.lang.OutOfMemoryError: Java heap space"));
        assertThat(v.status()).isEqualTo("MEMORY_LIMIT_EXCEEDED");
    }

    @Test
    void peakMemoryAboveLimitFailsEvenIfAnswersAreRight() {
        assertThat(decide(run(0, 100, 300 * 1024, List.of("1", "4", "9"), "")).status()).isEqualTo("MEMORY_LIMIT_EXCEEDED");
    }

    @Test
    void compileErrorWins() {
        var r = new RunResult("Main.java:3: error: ';' expected", false, false, -1, 0, 0, List.of(), "", 0, "", 0);
        assertThat(decide(r).status()).isEqualTo("COMPILATION_ERROR");
    }

    @Test
    void wallClockKillIsTimeLimit() {
        var r = new RunResult(null, false, true, -1, 0, 0, List.of(), "", 0, "", -1);
        assertThat(decide(r).status()).isEqualTo("TIME_LIMIT_EXCEEDED");
    }
}
```

The red-team suite throws real attacks at the real sandbox. It is excluded from the normal build and runs with
`-Psandbox`:

**File:** `services/judge-worker/src/test/java/dev/codearena/judge/SandboxRedTeamTest.java`

```java
package dev.codearena.judge;

import dev.codearena.codegen.CodeGenerator;
import dev.codearena.codegen.Lang;
import dev.codearena.codegen.Signature;
import dev.codearena.common.events.Limits;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Malicious and broken programs against the REAL sandbox. Needs Docker and the runner images:
 *   sandbox-images/build-all.sh python
 *   ./gradlew :services:judge-worker:test -Psandbox
 * Set JUDGE_RUNTIME=runc if gVisor is not installed (then these prove the Docker limits only).
 */
@Tag("sandbox")
class SandboxRedTeamTest {
    private static final Signature SIG = new Signature("double_it",
            List.of(new Signature.Param("x", "int")), "int", "JSON");
    private static final List<String> INPUTS = List.of("{\"x\":1}", "{\"x\":2}");
    private static final List<String> EXPECTED = List.of("2", "4");

    private final Sandbox sandbox = new Sandbox(new JudgeProps(
            System.getenv().getOrDefault("JUDGE_RUNTIME", "runsc"), System.getProperty("java.io.tmpdir") + "/ca-redteam",
            "http://unused", "unused", "unused", 65536, List.of()));
    private final CodeGenerator codegen = new CodeGenerator();

    private Verdict judge(String body) throws Exception {
        String code = "class Solution:\n    def double_it(self, x: int) -> int:\n" + body.indent(8);
        RunResult r = sandbox.run(Lang.PYTHON, code, codegen.harness(Lang.PYTHON, SIG), INPUTS, new Limits(2000, 128));
        return Judge.decide(r, INPUTS, EXPECTED, "JSON", 2200, 128, 65536);
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', textBlock = """
            correct solution        | return x * 2                                                            | ACCEPTED
            wrong answer            | return x + 2                                                            | WRONG_ANSWER
            infinite loop           | while True: pass                                                        | TIME_LIMIT_EXCEEDED
            fork bomb               | import os\\nwhile True: os.fork()                                        | RUNTIME_ERROR
            memory bomb             | x = [1] * (300 * 1024 * 1024)\\nreturn 0                               | MEMORY_LIMIT_EXCEEDED
            network call            | import urllib.request\\nurllib.request.urlopen('http://1.1.1.1', timeout=2)\\nreturn x * 2 | RUNTIME_ERROR
            cloud metadata          | import urllib.request\\nurllib.request.urlopen('http://169.254.169.254/', timeout=2)\\nreturn x * 2 | RUNTIME_ERROR
            write outside tmp       | open('/etc/hacked', 'w').write('x')\\nreturn x * 2                      | RUNTIME_ERROR
            fill tmp disk           | f = open('/tmp/big', 'wb')\\nwhile True: f.write(b'0' * 1048576)        | RUNTIME_ERROR
            output flood            | import sys\\nwhile True: sys.stdout.write('x' * 10000)                  | OUTPUT_LIMIT_EXCEEDED
            syntax error            | return x *                                                              | COMPILATION_ERROR
            """)
    void sandboxContainsIt(String name, String body, String expected) throws Exception {
        Verdict v = judge(body.replace("\\n", "\n"));
        assertThat(v.status()).as(name + " -> " + v.message()).isEqualTo(expected);
    }
}
```

## Check

```bash
./gradlew :services:judge-worker:test                       # logic tests
./gradlew :services:judge-worker:test -Psandbox             # red team: needs the python runner image
./gradlew :services:judge-worker:bootJar && java -jar services/judge-worker/build/libs/judge-worker.jar
```

The `PENDING` submission from chapter 08 is picked up within seconds and becomes `WRONG_ANSWER`
(`return True` is wrong for `"([)]"`). Submit a correct `isBalanced` and it is `ACCEPTED`.
