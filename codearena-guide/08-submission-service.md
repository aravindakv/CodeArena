# 08 · Submission service: accept, store, queue

The rule that makes 100,000 fest users possible: **nothing in the request path waits for code to run.**
`POST /v1/submissions` validates, stores the code in S3, writes a `PENDING` item, puts a message on a queue and
returns `202 Accepted` with an id, in a few milliseconds. The client then polls `GET /v1/submissions/{id}`.

| Check | Where | Why |
| --- | --- | --- |
| Code ≤ 64 KB, known language, valid custom input | here | cheap rejection before anything is stored |
| Problem published (admins may test unpublished ones) | judge metadata, cached 5 min in Redis | problem-service is not hit per submission |
| Contest live, problem in the contest, user registered | Redis keys kept by contest-service | no HTTP call on the hot path during a fest |
| Retried request | `Idempotency-Key` header, remembered 1 h | flaky mobile networks never create two submissions |
| Too many submits | gateway rate limiter, 1/s per user | a script cannot flood the judge |

The call to problem-service goes through a plain Resilience4j circuit breaker created in code. Nothing here needs
Spring's AOP-based `@CircuitBreaker` annotation, so the service has one dependency less. Boot 4 also moved the
auto-configured `RestClient.Builder` into its own starter, so the client is created with `RestClient.create(url)`.

Contest submissions go to `contest-submissions`, practice to `practice-submissions`, so practice traffic can never
delay a fest.

**Run** (`mode: RUN`) judges only the visible examples plus an optional custom input, is not recorded in stats, and
expires after a day (DynamoDB TTL). **Submit** runs every hidden test and counts.

**File:** `services/submission-service/build.gradle.kts`

```kotlin
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
```

**File:** `services/submission-service/src/main/resources/application.yml`

```yaml
server:
  port: ${PORT:9083}
spring:
  application:
    name: submission-service
  config:
    import: classpath:codearena-common.yml

codearena:
  problem-url: ${PROBLEM_URL:http://localhost:9082}
  submissions-bucket: codearena-submissions
  max-code-bytes: 65536
```

**File:** `services/submission-service/src/main/java/dev/codearena/submission/SubmissionApplication.java`

```java
package dev.codearena.submission;

import dev.codearena.common.web.ApiExceptionHandler;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

@SpringBootApplication
@Import(ApiExceptionHandler.class)
public class SubmissionApplication {
    public static void main(String[] args) {
        SpringApplication.run(SubmissionApplication.class, args);
    }
}
```

**File:** `services/submission-service/src/main/java/dev/codearena/submission/SecurityConfig.java`

```java
package dev.codearena.submission;

import dev.codearena.common.security.Roles;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
class SecurityConfig {
    @Bean
    SecurityFilterChain security(HttpSecurity http) throws Exception {
        return http
                .csrf(c -> c.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a
                        .requestMatchers("/error").permitAll()
                        .requestMatchers("/actuator/health/**").permitAll()
                        .requestMatchers("/v1/submissions", "/v1/submissions/**").hasAnyRole(Roles.USER, Roles.ADMIN)
                        .anyRequest().denyAll())
                .oauth2ResourceServer(o -> o.jwt(j -> j.jwtAuthenticationConverter(Roles.jwtConverter())))
                .build();
    }
}
```

**File:** `services/submission-service/src/main/java/dev/codearena/submission/ProblemClient.java`

```java
package dev.codearena.submission;

import tools.jackson.databind.json.JsonMapper;
import dev.codearena.common.events.Limits;
import dev.codearena.common.web.ApiException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;

/**
 * Fetches judge metadata from problem-service (/internal/...) and caches it in Redis for 5 minutes,
 * so a contest burst of thousands of submissions per second does not hammer problem-service.
 * A circuit breaker stops calling problem-service while it is failing and answers 503 at once.
 */
@Component
class ProblemClient {
    private final RestClient http;
    private final StringRedisTemplate redis;
    private final JsonMapper json;
    private final CircuitBreaker breaker = CircuitBreaker.of("problems", CircuitBreakerConfig.custom()
            .slidingWindowSize(20)
            .minimumNumberOfCalls(10)
            .failureRateThreshold(50)
            .waitDurationInOpenState(Duration.ofSeconds(10))
            .ignoreExceptions(ApiException.class)          // a 404 is an answer, not a failure
            .build());

    ProblemClient(@Value("${codearena.problem-url}") String baseUrl, StringRedisTemplate redis, JsonMapper json) {
        this.http = RestClient.create(baseUrl);
        this.redis = redis;
        this.json = json;
    }

    record Signature(String function, List<Object> params, String returns, String compare) {}

    record JudgeMeta(String slug, String status, int testsVersion, Limits limits, Signature signature,
                     String difficulty, List<String> tags, List<String> visibleInputs, List<String> visibleOutputs) {}

    JudgeMeta meta(String slug) {
        String key = "judge-meta:" + slug;
        try {
            String cached = redis.opsForValue().get(key);
            if (cached != null) return json.readValue(cached, JudgeMeta.class);
        } catch (Exception ignored) {
            // cache miss or Redis down: fall through to the service
        }
        JudgeMeta meta;
        try {
            meta = breaker.executeSupplier(() -> {
                try {
                    return http.get().uri("/internal/problems/{slug}/judge-meta", slug).retrieve().body(JudgeMeta.class);
                } catch (HttpClientErrorException.NotFound e) {
                    throw ApiException.notFound("problem");
                }
            });
        } catch (CallNotPermittedException | ResourceAccessException e) {
            throw unavailable();
        }
        try {
            redis.opsForValue().set(key, json.writeValueAsString(meta), Duration.ofMinutes(5));
        } catch (Exception ignored) {
            // caching is best effort
        }
        return meta;
    }

    static ApiException unavailable() {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "PROBLEMS_UNAVAILABLE", "try again shortly");
    }
}
```

**File:** `services/submission-service/src/main/java/dev/codearena/submission/ContestGate.java`

```java
package dev.codearena.submission;

import dev.codearena.common.web.ApiException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Map;

/**
 * Contest checks straight from Redis (contest-service keeps these keys up to date), so the hot submit path
 * needs no extra HTTP call during a fest:
 *   contest:{cid}:meta  hash  startMs, endMs, problems (comma separated slugs)
 *   contest:{cid}:reg   set   registered user ids
 */
@Component
class ContestGate {
    private final StringRedisTemplate redis;

    ContestGate(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** Returns the contest start (epoch ms) if the user may submit this problem now; throws otherwise. */
    long requireOpen(String contestId, String userId, String slug) {
        Map<Object, Object> meta = redis.opsForHash().entries("contest:{" + contestId + "}:meta");
        if (meta.isEmpty()) throw ApiException.notFound("contest");
        long now = System.currentTimeMillis();
        long start = Long.parseLong((String) meta.get("startMs"));
        long end = Long.parseLong((String) meta.get("endMs"));
        if (now < start || now >= end) throw ApiException.forbidden("CONTEST_NOT_LIVE", "the contest is not running");
        if (!Arrays.asList(((String) meta.get("problems")).split(",")).contains(slug))
            throw ApiException.badRequest("NOT_IN_CONTEST", "this problem is not part of the contest");
        if (!Boolean.TRUE.equals(redis.opsForSet().isMember("contest:{" + contestId + "}:reg", userId)))
            throw ApiException.forbidden("NOT_REGISTERED", "register for the contest first");
        return start;
    }
}
```

**File:** `services/submission-service/src/main/java/dev/codearena/submission/SubmissionRepository.java`

```java
package dev.codearena.submission;

import org.springframework.stereotype.Repository;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static dev.codearena.common.aws.Ddb.*;

/**
 * Submissions table: PK=USER#<userId> SK=SUB#<ulid>  (ULIDs sort by time, so a query returns newest first)
 * RUN-mode items carry a TTL so DynamoDB deletes them after a day.
 * judge-worker writes the verdict fields onto the same item.
 */
@Repository
class SubmissionRepository {
    private static final String TABLE = "Submissions";
    private final DynamoDbClient ddb;

    SubmissionRepository(DynamoDbClient ddb) {
        this.ddb = ddb;
    }

    record Submission(String id, String slug, String lang, String mode, String contestId, String status,
                      String createdAt, long runtimeMs, long memoryKb, int passed, int total, String message,
                      String failedInput, String expected, String actual, String stdout, String customOutput) {}

    void createPending(String id, String userId, String slug, String lang, String mode, String contestId) {
        Map<String, AttributeValue> item = new HashMap<>(key("USER#" + userId, "SUB#" + id));
        item.put("submissionId", s(id));
        item.put("slug", s(slug));
        item.put("lang", s(lang));
        item.put("mode", s(mode));
        item.put("status", s("PENDING"));
        item.put("createdAt", s(Instant.now().toString()));
        if (contestId != null) item.put("contestId", s(contestId));
        if ("RUN".equals(mode)) item.put("ttl", n(Instant.now().plusSeconds(86_400).getEpochSecond()));
        ddb.putItem(r -> r.tableName(TABLE).item(item));
    }

    Optional<Submission> find(String userId, String id) {
        var item = ddb.getItem(r -> r.tableName(TABLE).key(key("USER#" + userId, "SUB#" + id))).item();
        return has(item) ? Optional.of(map(item)) : Optional.empty();
    }

    List<Submission> recent(String userId, String slug, int limit) {
        var req = QueryRequest.builder().tableName(TABLE)
                .keyConditionExpression("PK = :p AND begins_with(SK, :s)")
                .filterExpression(slug == null ? "#m = :submit" : "#m = :submit AND slug = :slug")
                .expressionAttributeNames(Map.of("#m", "mode"))
                .expressionAttributeValues(slug == null
                        ? Map.of(":p", s("USER#" + userId), ":s", s("SUB#"), ":submit", s("SUBMIT"))
                        : Map.of(":p", s("USER#" + userId), ":s", s("SUB#"), ":submit", s("SUBMIT"), ":slug", s(slug)))
                .scanIndexForward(false)
                .limit(200)
                .build();
        return ddb.query(req).items().stream().limit(limit).map(SubmissionRepository::map).toList();
    }

    private static Submission map(Map<String, AttributeValue> i) {
        return new Submission(str(i, "submissionId"), str(i, "slug"), str(i, "lang"), str(i, "mode"),
                str(i, "contestId"), str(i, "status"), str(i, "createdAt"), num(i, "runtimeMs"), num(i, "memoryKb"),
                (int) num(i, "passed"), (int) num(i, "total"), str(i, "message"), str(i, "failedInput"),
                str(i, "expected"), str(i, "actual"), str(i, "stdout"), str(i, "customOutput"));
    }
}
```

**File:** `services/submission-service/src/main/java/dev/codearena/submission/SubmissionController.java`

```java
package dev.codearena.submission;

import com.github.f4b6a3.ulid.UlidCreator;
import tools.jackson.databind.json.JsonMapper;
import dev.codearena.codegen.Lang;
import dev.codearena.common.events.Queues;
import dev.codearena.common.events.SubmissionCreated;
import dev.codearena.common.web.ApiException;
import io.awspring.cloud.sqs.operations.SqsTemplate;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import software.amazon.awssdk.services.s3.S3Client;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Accepts code, stores it, queues it, returns 202 + an id. Nothing here runs code: the judge does,
 * on its own hosts, and writes the verdict onto the submission item. Clients poll GET /{id}.
 */
@RestController
@RequestMapping("/v1/submissions")
class SubmissionController {
    private final ProblemClient problems;
    private final ContestGate contests;
    private final SubmissionRepository repo;
    private final S3Client s3;
    private final SqsTemplate sqs;
    private final StringRedisTemplate redis;
    private final JsonMapper json;
    private final String bucket;
    private final int maxCodeBytes;

    SubmissionController(ProblemClient problems, ContestGate contests, SubmissionRepository repo, S3Client s3,
                         SqsTemplate sqs, StringRedisTemplate redis, JsonMapper json,
                         @Value("${codearena.submissions-bucket}") String bucket,
                         @Value("${codearena.max-code-bytes}") int maxCodeBytes) {
        this.problems = problems;
        this.contests = contests;
        this.repo = repo;
        this.s3 = s3;
        this.sqs = sqs;
        this.redis = redis;
        this.json = json;
        this.bucket = bucket;
        this.maxCodeBytes = maxCodeBytes;
    }

    record SubmitRequest(@NotBlank String slug, @NotBlank String lang, @NotBlank String code,
                         @Pattern(regexp = "RUN|SUBMIT") String mode, String contestId, String customInput) {}

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    Map<String, String> submit(@Valid @RequestBody SubmitRequest r, @AuthenticationPrincipal Jwt jwt,
                               @RequestHeader(value = "Idempotency-Key", required = false) String idemKey) {
        String userId = jwt.getSubject();
        String idemRedisKey = idemKey == null ? null : "idem:" + userId + ":" + idemKey;
        if (idemRedisKey != null) {
            String previous = redis.opsForValue().get(idemRedisKey);
            if (previous != null) return Map.of("submissionId", previous);   // client retried: same answer
        }
        if (r.code().getBytes(StandardCharsets.UTF_8).length > maxCodeBytes)
            throw ApiException.badRequest("CODE_TOO_LARGE", "code is limited to " + maxCodeBytes / 1024 + " KB");
        Lang lang = Lang.of(r.lang());
        String mode = r.mode() == null ? "SUBMIT" : r.mode();

        var meta = problems.meta(r.slug());
        if (!"PUBLISHED".equals(meta.status()) && !isAdmin(jwt)) throw ApiException.notFound("problem");
        if (r.customInput() != null && !r.customInput().isBlank()) validateCustomInput(r.customInput());

        long contestStart = 0;
        if (r.contestId() != null && !r.contestId().isBlank()) {
            if ("RUN".equals(mode)) throw ApiException.badRequest("RUN_IN_CONTEST", "use RUN without contestId");
            contestStart = contests.requireOpen(r.contestId(), userId, r.slug());
        }

        String id = UlidCreator.getMonotonicUlid().toString();
        s3.putObject(b -> b.bucket(bucket).key("src/" + id), software.amazon.awssdk.core.sync.RequestBody.fromString(r.code(), StandardCharsets.UTF_8));
        String contestId = contestStart > 0 ? r.contestId() : null;
        repo.createPending(id, userId, r.slug(), lang.id, mode, contestId);

        var event = new SubmissionCreated(id, userId, r.slug(), lang.id, mode, contestId, contestStart,
                meta.testsVersion(), meta.limits(), meta.signature().compare(), meta.difficulty(), meta.tags(),
                "RUN".equals(mode) ? r.customInput() : null, System.currentTimeMillis());
        sqs.send(contestId != null ? Queues.CONTEST_SUBMISSIONS : Queues.PRACTICE_SUBMISSIONS, event);

        if (idemRedisKey != null) redis.opsForValue().set(idemRedisKey, id, Duration.ofHours(1));
        return Map.of("submissionId", id);
    }

    @GetMapping("/{id}")
    SubmissionRepository.Submission get(@PathVariable String id, @AuthenticationPrincipal Jwt jwt) {
        return repo.find(jwt.getSubject(), id).orElseThrow(() -> ApiException.notFound("submission"));
    }

    @GetMapping
    List<SubmissionRepository.Submission> mine(@AuthenticationPrincipal Jwt jwt,
                                                @RequestParam(required = false) String slug,
                                                @RequestParam(defaultValue = "20") int limit) {
        return repo.recent(jwt.getSubject(), slug, Math.min(limit, 50));
    }

    private void validateCustomInput(String line) {
        if (line.length() > 8192) throw ApiException.badRequest("INPUT_TOO_LARGE", "custom input is limited to 8 KB");
        try {
            if (!json.readTree(line).isObject()) throw new IllegalArgumentException();
        } catch (Exception e) {
            throw ApiException.badRequest("BAD_CUSTOM_INPUT", "custom input must be a JSON object, e.g. {\"nums\":[1,2],\"target\":3}");
        }
    }

    private static boolean isAdmin(Jwt jwt) {
        List<String> roles = jwt.getClaimAsStringList("roles");
        return roles != null && roles.contains("ADMIN");
    }
}
```

## Check (gateway, auth, problem and submission running)

Use the login from chapter 05 to get `$TOKEN`, then:

```bash
curl -s -X POST localhost:9080/api/v1/submissions -H "authorization: Bearer $TOKEN" -H 'content-type: application/json' \
  -d '{"slug":"balanced-brackets","lang":"python","mode":"SUBMIT","code":"class Solution:\n    def isBalanced(self, s):\n        return True\n"}'
# {"submissionId":"01J..."}
curl -s localhost:9080/api/v1/submissions/<id> -H "authorization: Bearer $TOKEN"     # "status":"PENDING"
```

It stays `PENDING` until the judge exists (next chapter). The message is waiting in http://localhost:9707.
