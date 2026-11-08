# 06 · Problem service: catalog, 100 per page, starter code, admin review

problem-service owns problems: the public catalog, starter code, the admin review workflow, and dispatching
AI ingestion runs (chapter 14).

## How the list stays fast

The published list is cached in Redis as **one JSON array** (a few hundred KB even at 5,000 problems). Paging
(100 per page), the difficulty and topic filters, and title search all run in memory on that list, so the hottest
endpoint never touches DynamoDB. Publishing or retiring a problem increments `problems:list:version`; the cache key
includes the version, so every stale copy is abandoned at once, and the TTL (10 minutes) cleans them up.
The `byNumber` index exists for rebuilding the cache, and for cursor-style paging if the catalog ever grows past what
fits in memory.

## Lifecycle

```
DRAFT (manual) ─┐
                ├──publish──▶ PUBLISHED ──retire──▶ RETIRED
IN_REVIEW (AI) ─┘     │
        └──reject──▶ REJECTED
```

Publishing requires uploaded hidden tests, and assigns the next problem number with an atomic DynamoDB counter.
Test files are immutable and versioned in S3 (`<slug>/v<n>/cases.jsonl`), so a judge mid-run never sees a
half-written file.

## The service

**File:** `services/problem-service/build.gradle.kts`

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
    implementation("com.github.f4b6a3:ulid-creator:${Versions.ULID}")
}
```

**File:** `services/problem-service/src/main/resources/application.yml`

```yaml
server:
  port: ${PORT:9082}
spring:
  application:
    name: problem-service
  config:
    import: classpath:codearena-common.yml

codearena:
  tests-bucket: codearena-tests
  problem-list-cache-ttl: 10m
```

**File:** `services/problem-service/src/main/java/dev/codearena/problem/ProblemApplication.java`

```java
package dev.codearena.problem;

import dev.codearena.codegen.CodeGenerator;
import dev.codearena.common.web.ApiExceptionHandler;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

@SpringBootApplication
@Import(ApiExceptionHandler.class)
public class ProblemApplication {
    public static void main(String[] args) {
        SpringApplication.run(ProblemApplication.class, args);
    }

    @Bean
    CodeGenerator codeGenerator() {
        return new CodeGenerator();
    }
}
```

`/internal/**` is for service-to-service calls; the gateway never routes it.

**File:** `services/problem-service/src/main/java/dev/codearena/problem/SecurityConfig.java`

```java
package dev.codearena.problem;

import dev.codearena.common.security.Roles;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * /internal/** is for service-to-service calls (submission-service, judge-worker, ingestion worker).
 * The gateway never routes it; on Kubernetes a NetworkPolicy must also keep it cluster-internal.
 */
@Configuration
@EnableMethodSecurity
class SecurityConfig {
    @Bean
    SecurityFilterChain security(HttpSecurity http) throws Exception {
        return http
                .csrf(c -> c.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a
                        .requestMatchers("/error").permitAll()
                        .requestMatchers(HttpMethod.GET, "/v1/problems", "/v1/problems/**", "/v1/languages").permitAll()
                        .requestMatchers("/internal/**", "/actuator/health/**").permitAll()
                        .requestMatchers("/v1/admin/**").hasRole(Roles.ADMIN)
                        .anyRequest().authenticated())
                .oauth2ResourceServer(o -> o.jwt(j -> j.jwtAuthenticationConverter(Roles.jwtConverter())))
                .build();
    }
}
```

**File:** `services/problem-service/src/main/java/dev/codearena/problem/domain/ProblemCatalog.java`

```java
package dev.codearena.problem.domain;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * The published problem list, cached in Redis as one JSON array (a few hundred KB even at 5,000 problems).
 * Paging, difficulty/tag filters and title search run in memory on that list, so the list endpoint never
 * touches DynamoDB on the hot path. Publishing or retiring a problem bumps a version number, which makes
 * every cached copy stale at once.
 */
@Service
public class ProblemCatalog {
    private static final Logger log = LoggerFactory.getLogger(ProblemCatalog.class);
    private static final String VERSION_KEY = "problems:list:version";
    private static final TypeReference<List<ProblemSummary>> LIST = new TypeReference<>() {};

    private final ProblemRepository repo;
    private final StringRedisTemplate redis;
    private final JsonMapper json;
    private final Duration ttl;

    public ProblemCatalog(ProblemRepository repo, StringRedisTemplate redis, JsonMapper json,
                          @Value("${codearena.problem-list-cache-ttl}") Duration ttl) {
        this.repo = repo;
        this.redis = redis;
        this.json = json;
        this.ttl = ttl;
    }

    public record Page(List<ProblemSummary> items, int page, int size, int totalItems, int totalPages) {}

    public Page page(int page, int size, String difficulty, String tag, String q) {
        List<ProblemSummary> all = all().stream()
                .filter(p -> difficulty == null || difficulty.isBlank() || p.difficulty().equalsIgnoreCase(difficulty))
                .filter(p -> tag == null || tag.isBlank() || p.tags().contains(tag))
                .filter(p -> q == null || q.isBlank() || p.title().toLowerCase(Locale.ROOT).contains(q.toLowerCase(Locale.ROOT))
                        || String.valueOf(p.number()).equals(q.trim()))
                .toList();
        int totalPages = Math.max(1, (all.size() + size - 1) / size);
        int from = Math.min((page - 1) * size, all.size());
        return new Page(all.subList(from, Math.min(from + size, all.size())), page, size, all.size(), totalPages);
    }

    public List<String> tags() {
        return all().stream().flatMap(p -> p.tags().stream()).distinct().sorted().toList();
    }

    List<ProblemSummary> all() {
        String key = "problems:list:v" + version();
        try {
            String cached = redis.opsForValue().get(key);
            if (cached != null) return json.readValue(cached, LIST);
        } catch (Exception e) {
            log.warn("problem list cache unavailable ({}); reading DynamoDB", e.getMessage());
        }
        List<ProblemSummary> fresh = repo.allPublished().stream()
                .map(r -> new ProblemSummary(r.number(), r.doc().slug(), r.doc().title(), r.doc().difficulty(),
                        r.doc().tags(), r.acceptance()))
                .toList();
        try {
            redis.opsForValue().set(key, json.writeValueAsString(fresh), ttl);
        } catch (Exception e) {
            log.warn("could not cache problem list: {}", e.getMessage());
        }
        return fresh;
    }

    public void invalidate() {
        try {
            redis.opsForValue().increment(VERSION_KEY);
        } catch (Exception e) {
            log.warn("could not invalidate problem list cache: {}", e.getMessage());
        }
    }

    private String version() {
        try {
            String v = redis.opsForValue().get(VERSION_KEY);
            return v == null ? "0" : v;
        } catch (Exception e) {
            return "0";
        }
    }

    static List<String> split(String csv) {
        return csv == null || csv.isBlank() ? List.of() : Arrays.asList(csv.split(","));
    }
}
```

**File:** `services/problem-service/src/main/java/dev/codearena/problem/domain/ProblemDoc.java`

```java
package dev.codearena.problem.domain;

import dev.codearena.codegen.Signature;
import dev.codearena.common.events.Limits;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.util.List;

/**
 * The full problem as stored in the "doc" attribute (JSON). Hidden test cases are NOT here;
 * they live in S3 at codearena-tests/<slug>/v<testsVersion>/cases.jsonl.
 */
public record ProblemDoc(
        @NotBlank @Pattern(regexp = "[a-z0-9]+(-[a-z0-9]+)*") String slug,
        @NotBlank String title,
        @NotBlank @Pattern(regexp = "EASY|MEDIUM|HARD") String difficulty,
        @NotNull List<String> tags,
        @NotBlank String statement,
        @NotNull List<String> constraints,
        @NotEmpty List<@Valid Example> examples,
        @NotNull Signature signature,
        Limits limits,
        List<String> hints) {

    public record Example(@NotBlank String input, @NotBlank String output, String explanation) {}

    public Limits limitsOrDefault() {
        return limits == null ? Limits.DEFAULT : limits;
    }
}
```

**File:** `services/problem-service/src/main/java/dev/codearena/problem/domain/ProblemRecord.java`

```java
package dev.codearena.problem.domain;

/** A Problems table META item: the doc plus lifecycle fields. */
public record ProblemRecord(ProblemDoc doc, String status, int number, int testsVersion, String source,
                           long submits, long accepts, String updatedAt) {

    public boolean published() {
        return "PUBLISHED".equals(status);
    }

    public double acceptance() {
        return submits == 0 ? 0 : Math.round(1000.0 * accepts / submits) / 10.0;
    }
}
```

**File:** `services/problem-service/src/main/java/dev/codearena/problem/domain/ProblemRepository.java`

```java
package dev.codearena.problem.domain;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;
import dev.codearena.common.web.ApiException;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.ReturnValue;
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static dev.codearena.common.aws.Ddb.*;

/**
 * Problems table
 *   PK=PROBLEM#<slug> SK=META   the problem (doc JSON + status, number, testsVersion, counters)
 *   PK=COUNTER        SK=PROBLEM_NUMBER   next problem number (atomic ADD at publish time)
 * GSI byNumber: listPk="LIST#PUBLISHED" (only set when published) + number "00042" (zero padded, sorts as text)
 */
@Repository
public class ProblemRepository {
    public static final String TABLE = "Problems";
    private static final String LIST_PK = "LIST#PUBLISHED";

    private final DynamoDbClient ddb;
    private final JsonMapper json;

    public ProblemRepository(DynamoDbClient ddb, JsonMapper json) {
        this.ddb = ddb;
        this.json = json;
    }

    public static String pad(int n) {
        return String.format("%05d", n);
    }

    public Optional<ProblemRecord> find(String slug) {
        var item = ddb.getItem(r -> r.tableName(TABLE).key(key("PROBLEM#" + slug, "META"))).item();
        return has(item) ? Optional.of(map(item)) : Optional.empty();
    }

    /** All published problems in number order (pages through the GSI). */
    public List<ProblemRecord> allPublished() {
        List<ProblemRecord> out = new ArrayList<>();
        Map<String, AttributeValue> start = null;
        do {
            var req = QueryRequest.builder().tableName(TABLE).indexName("byNumber")
                    .keyConditionExpression("listPk = :p")
                    .expressionAttributeValues(Map.of(":p", s(LIST_PK)))
                    .exclusiveStartKey(start).build();
            var res = ddb.query(req);
            res.items().forEach(i -> out.add(map(i)));
            start = res.hasLastEvaluatedKey() && !res.lastEvaluatedKey().isEmpty() ? res.lastEvaluatedKey() : null;
        } while (start != null);
        return out;
    }

    /** Admin view by status. A Scan is fine locally; add a GSI on status when the table grows. */
    public List<ProblemRecord> byStatus(String status) {
        List<ProblemRecord> out = new ArrayList<>();
        Map<String, AttributeValue> start = null;
        do {
            var req = ScanRequest.builder().tableName(TABLE)
                    .filterExpression("SK = :m AND #st = :s")
                    .expressionAttributeNames(Map.of("#st", "status"))
                    .expressionAttributeValues(Map.of(":m", s("META"), ":s", s(status)))
                    .exclusiveStartKey(start).build();
            var res = ddb.scan(req);
            res.items().forEach(i -> out.add(map(i)));
            start = res.hasLastEvaluatedKey() && !res.lastEvaluatedKey().isEmpty() ? res.lastEvaluatedKey() : null;
        } while (start != null);
        return out;
    }

    /** Creates a new problem (DRAFT or IN_REVIEW). Fails if the slug exists. */
    public void create(ProblemDoc doc, String status, String source) {
        Map<String, AttributeValue> item = new HashMap<>(key("PROBLEM#" + doc.slug(), "META"));
        item.putAll(docAttributes(doc));
        item.put("status", s(status));
        item.put("testsVersion", n(0));
        item.put("source", s(source));
        item.put("createdAt", s(Instant.now().toString()));
        try {
            ddb.putItem(r -> r.tableName(TABLE).item(item).conditionExpression("attribute_not_exists(PK)"));
        } catch (ConditionalCheckFailedException e) {
            throw ApiException.conflict("SLUG_TAKEN", "a problem with slug " + doc.slug() + " already exists");
        }
    }

    public void updateDoc(ProblemDoc doc) {
        Map<String, AttributeValue> values = new HashMap<>();
        Map<String, String> names = new HashMap<>();
        StringBuilder expr = new StringBuilder("SET ");
        int i = 0;
        for (var e : docAttributes(doc).entrySet()) {
            if (i++ > 0) expr.append(", ");
            expr.append("#a").append(i).append(" = :v").append(i);
            names.put("#a" + i, e.getKey());
            values.put(":v" + i, e.getValue());
        }
        ddb.updateItem(r -> r.tableName(TABLE).key(key("PROBLEM#" + doc.slug(), "META"))
                .updateExpression(expr.toString()).conditionExpression("attribute_exists(PK)")
                .expressionAttributeNames(names).expressionAttributeValues(values));
    }

    public void setTestsVersion(String slug, int version) {
        ddb.updateItem(r -> r.tableName(TABLE).key(key("PROBLEM#" + slug, "META"))
                .updateExpression("SET testsVersion = :v, updatedAt = :u")
                .expressionAttributeValues(Map.of(":v", n(version), ":u", s(Instant.now().toString()))));
    }

    public void setStatus(String slug, String status) {
        ddb.updateItem(r -> r.tableName(TABLE).key(key("PROBLEM#" + slug, "META"))
                .updateExpression("SET #st = :s, updatedAt = :u")
                .expressionAttributeNames(Map.of("#st", "status"))
                .expressionAttributeValues(Map.of(":s", s(status), ":u", s(Instant.now().toString()))));
    }

    /** Publishes: takes the next number atomically and puts the item on the byNumber index. */
    public int publish(String slug) {
        var counter = ddb.updateItem(r -> r.tableName(TABLE).key(key("COUNTER", "PROBLEM_NUMBER"))
                .updateExpression("ADD #v :one")
                .expressionAttributeNames(Map.of("#v", "value"))
                .expressionAttributeValues(Map.of(":one", n(1)))
                .returnValues(ReturnValue.UPDATED_NEW));
        int number = Integer.parseInt(counter.attributes().get("value").n());
        ddb.updateItem(r -> r.tableName(TABLE).key(key("PROBLEM#" + slug, "META"))
                .updateExpression("SET #st = :s, #num = :n, listPk = :l, updatedAt = :u")
                .conditionExpression("attribute_exists(PK) AND attribute_not_exists(listPk)")
                .expressionAttributeNames(Map.of("#st", "status", "#num", "number"))
                .expressionAttributeValues(Map.of(":s", s("PUBLISHED"), ":n", s(pad(number)),
                        ":l", s(LIST_PK), ":u", s(Instant.now().toString()))));
        return number;
    }

    /** Takes a problem off the list (keeps its number so links and stats stay valid). */
    public void retire(String slug) {
        ddb.updateItem(r -> r.tableName(TABLE).key(key("PROBLEM#" + slug, "META"))
                .updateExpression("SET #st = :s REMOVE listPk")
                .expressionAttributeNames(Map.of("#st", "status"))
                .expressionAttributeValues(Map.of(":s", s("RETIRED"))));
    }

    private Map<String, AttributeValue> docAttributes(ProblemDoc doc) {
        return Map.of(
                "slug", s(doc.slug()),
                "title", s(doc.title()),
                "difficulty", s(doc.difficulty()),
                "tags", s(String.join(",", doc.tags())),
                "doc", s(json.writeValueAsString(doc)),     // Jackson 3: exceptions are unchecked
                "updatedAt", s(Instant.now().toString()));
    }

    private ProblemRecord map(Map<String, AttributeValue> i) {
        try {
            ProblemDoc doc = json.readValue(str(i, "doc"), ProblemDoc.class);
            String num = str(i, "number");
            return new ProblemRecord(doc, str(i, "status"), num == null ? 0 : Integer.parseInt(num),
                    (int) num(i, "testsVersion"), str(i, "source"), num(i, "submits"), num(i, "accepts"),
                    str(i, "updatedAt"));
        } catch (JacksonException e) {
            throw new IllegalStateException("corrupt problem doc for " + str(i, "slug"), e);
        }
    }
}
```

**File:** `services/problem-service/src/main/java/dev/codearena/problem/domain/ProblemSummary.java`

```java
package dev.codearena.problem.domain;

import java.util.List;

/** One row of the problem list. */
public record ProblemSummary(int number, String slug, String title, String difficulty, List<String> tags,
                             double acceptance) {
}
```

**File:** `services/problem-service/src/main/java/dev/codearena/problem/domain/TestStore.java`

```java
package dev.codearena.problem.domain;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import dev.codearena.common.web.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

import java.util.Arrays;

/**
 * Hidden test cases in S3: <bucket>/<slug>/v<version>/cases.jsonl
 * Each line: {"input": {"<param>": value, ...}, "output": value}
 * A new upload always creates a new version, so a running judge never sees a half-written file.
 */
@Component
public class TestStore {
    private final S3Client s3;
    private final JsonMapper json;
    private final String bucket;

    public TestStore(S3Client s3, JsonMapper json, @Value("${codearena.tests-bucket}") String bucket) {
        this.s3 = s3;
        this.json = json;
        this.bucket = bucket;
    }

    public static String keyFor(String slug, int version) {
        return slug + "/v" + version + "/cases.jsonl";
    }

    /** Validates every line against the signature's parameter names and uploads. Returns the case count. */
    public int upload(String slug, int version, String jsonl, java.util.List<String> paramNames) {
        String[] lines = Arrays.stream(jsonl.split("\n")).filter(l -> !l.isBlank()).toArray(String[]::new);
        if (lines.length == 0) throw ApiException.badRequest("NO_TESTS", "at least one test case is required");
        for (int i = 0; i < lines.length; i++) {
            try {
                JsonNode n = json.readTree(lines[i]);
                if (!n.has("input") || !n.has("output")) throw new IllegalArgumentException("needs input and output");
                for (String p : paramNames)
                    if (!n.get("input").has(p)) throw new IllegalArgumentException("input lacks parameter " + p);
            } catch (Exception e) {
                throw ApiException.badRequest("BAD_TEST_CASE", "line " + (i + 1) + ": " + e.getMessage());
            }
        }
        s3.putObject(b -> b.bucket(bucket).key(keyFor(slug, version)).contentType("application/x-ndjson"),
                RequestBody.fromString(String.join("\n", lines) + "\n"));
        return lines.length;
    }

    public boolean exists(String slug, int version) {
        if (version <= 0) return false;
        try {
            s3.headObject(b -> b.bucket(bucket).key(keyFor(slug, version)));
            return true;
        } catch (NoSuchKeyException e) {
            return false;
        } catch (software.amazon.awssdk.services.s3.model.S3Exception e) {
            if (e.statusCode() == 404) return false;
            throw e;
        }
    }
}
```

**File:** `services/problem-service/src/main/java/dev/codearena/problem/api/ProblemController.java`

```java
package dev.codearena.problem.api;

import dev.codearena.codegen.CodeGenerator;
import dev.codearena.codegen.Lang;
import dev.codearena.common.events.Limits;
import dev.codearena.common.web.ApiException;
import dev.codearena.problem.domain.ProblemCatalog;
import dev.codearena.problem.domain.ProblemDoc;
import dev.codearena.problem.domain.ProblemRecord;
import dev.codearena.problem.domain.ProblemRepository;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;

/** Public, read-only API used by web and mobile clients. Paths are /v1/... (the gateway adds /api). */
@RestController
@RequestMapping("/v1")
@Validated
class ProblemController {
    private final ProblemCatalog catalog;
    private final ProblemRepository repo;
    private final CodeGenerator codegen;

    ProblemController(ProblemCatalog catalog, ProblemRepository repo, CodeGenerator codegen) {
        this.catalog = catalog;
        this.repo = repo;
        this.codegen = codegen;
    }

    record ProblemDetail(int number, String slug, String title, String difficulty, List<String> tags,
                         String statement, List<String> constraints, List<ProblemDoc.Example> examples,
                         List<String> hints, Limits limits, double acceptance, List<String> languages) {}

    record LanguageInfo(String id, String label, String monacoId) {}

    @GetMapping("/problems")
    ResponseEntity<ProblemCatalog.Page> list(@RequestParam(defaultValue = "1") @Min(1) int page,
                                             @RequestParam(defaultValue = "100") @Min(1) @Max(100) int size,
                                             @RequestParam(required = false) String difficulty,
                                             @RequestParam(required = false) String tag,
                                             @RequestParam(required = false) String q) {
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(Duration.ofSeconds(30)).cachePublic())
                .body(catalog.page(page, size, difficulty, tag, q));
    }

    @GetMapping("/problems/tags")
    List<String> tags() {
        return catalog.tags();
    }

    @GetMapping("/problems/{slug}")
    ResponseEntity<ProblemDetail> get(@PathVariable String slug) {
        ProblemRecord r = published(slug);
        ProblemDoc d = r.doc();
        var body = new ProblemDetail(r.number(), d.slug(), d.title(), d.difficulty(), d.tags(), d.statement(),
                d.constraints(), d.examples(), d.hints() == null ? List.of() : d.hints(), d.limitsOrDefault(),
                r.acceptance(), Arrays.stream(Lang.values()).map(l -> l.id).toList());
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(Duration.ofSeconds(60)).cachePublic())
                .eTag("\"" + r.updatedAt() + "\"").body(body);
    }

    @GetMapping(value = "/problems/{slug}/starter/{lang}", produces = MediaType.TEXT_PLAIN_VALUE)
    String starter(@PathVariable String slug, @PathVariable String lang) {
        return codegen.starter(Lang.of(lang), published(slug).doc().signature());
    }

    @GetMapping("/languages")
    List<LanguageInfo> languages() {
        return Arrays.stream(Lang.values()).map(l -> new LanguageInfo(l.id, l.label, l.monacoId)).toList();
    }

    private ProblemRecord published(String slug) {
        return repo.find(slug).filter(ProblemRecord::published).orElseThrow(() -> ApiException.notFound("problem"));
    }
}
```

The internal API gives the judge everything it needs, including the visible examples converted to JSON inputs
(the Run button tests those).

**File:** `services/problem-service/src/main/java/dev/codearena/problem/api/InternalController.java`

```java
package dev.codearena.problem.api;

import dev.codearena.codegen.CodeGenerator;
import dev.codearena.codegen.Lang;
import dev.codearena.codegen.Signature;
import dev.codearena.common.events.Limits;
import dev.codearena.common.web.ApiException;
import dev.codearena.problem.domain.ProblemRecord;
import dev.codearena.problem.domain.ProblemRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Service-to-service API. Not routed by the gateway. */
@RestController
@RequestMapping("/internal")
class InternalController {
    private final ProblemRepository repo;
    private final CodeGenerator codegen;

    InternalController(ProblemRepository repo, CodeGenerator codegen) {
        this.repo = repo;
        this.codegen = codegen;
    }

    /** Everything submission-service and judge-worker need to judge a problem. */
    record JudgeMeta(String slug, String status, int testsVersion, Limits limits, Signature signature,
                     String difficulty, List<String> tags, List<String> visibleInputs, List<String> visibleOutputs) {}

    record CodegenRequest(Signature signature, String lang) {}

    record CodegenResponse(String starter, String harness, String userFile, String harnessFile) {}

    @GetMapping("/problems/{slug}/judge-meta")
    JudgeMeta judgeMeta(@PathVariable String slug) {
        ProblemRecord r = repo.find(slug).orElseThrow(() -> ApiException.notFound("problem"));
        var d = r.doc();
        return new JudgeMeta(d.slug(), r.status(), r.testsVersion(), d.limitsOrDefault(), d.signature(),
                d.difficulty(), d.tags(),
                d.examples().stream().map(e -> ExampleInputs.toJson(e.input(), d.signature())).toList(),
                d.examples().stream().map(e -> e.output().trim()).toList());
    }

    /** Used by the AI ingestion worker to build harnesses for verification in every language. */
    @PostMapping("/codegen")
    CodegenResponse codegen(@RequestBody CodegenRequest req) {
        Lang lang = Lang.of(req.lang());
        return new CodegenResponse(codegen.starter(lang, req.signature()), codegen.harness(lang, req.signature()),
                lang.userFile, lang.harnessFile);
    }
}
```

**File:** `services/problem-service/src/main/java/dev/codearena/problem/api/ExampleInputs.java`

```java
package dev.codearena.problem.api;

import dev.codearena.codegen.Signature;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns a human-readable example input such as  nums = [3,1,4,6], target = 7
 * into the JSON test-input line  {"nums":[3,1,4,6],"target":7}  used by the RUN mode.
 * Values must already be JSON (numbers, "strings", [arrays], true/false).
 */
public final class ExampleInputs {
    private ExampleInputs() {}

    public static String toJson(String input, Signature sig) {
        List<String> values = splitTopLevel(input);
        if (values.size() != sig.params().size())
            throw new IllegalArgumentException("example input needs " + sig.params().size() + " values: " + input);
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < values.size(); i++) {
            String part = values.get(i);
            int eq = part.indexOf('=');
            String value = (eq >= 0 && part.substring(0, eq).trim().matches("[A-Za-z_][A-Za-z0-9_]*"))
                    ? part.substring(eq + 1).trim() : part.trim();
            if (i > 0) sb.append(',');
            sb.append('"').append(sig.params().get(i).name()).append("\":").append(value);
        }
        return sb.append('}').toString();
    }

    /** Splits on commas that are not inside brackets or quotes. */
    static List<String> splitTopLevel(String s) {
        List<String> out = new ArrayList<>();
        int depth = 0;
        boolean inString = false;
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' && (i == 0 || s.charAt(i - 1) != '\\')) inString = !inString;
            if (!inString) {
                if (c == '[' || c == '{') depth++;
                if (c == ']' || c == '}') depth--;
                if (c == ',' && depth == 0) {
                    out.add(cur.toString());
                    cur.setLength(0);
                    continue;
                }
            }
            cur.append(c);
        }
        if (!cur.toString().isBlank()) out.add(cur.toString());
        return out;
    }
}
```

**File:** `services/problem-service/src/main/java/dev/codearena/problem/admin/AdminProblemController.java`

```java
package dev.codearena.problem.admin;

import dev.codearena.codegen.CodeGenerator;
import dev.codearena.codegen.Lang;
import dev.codearena.common.web.ApiException;
import dev.codearena.problem.domain.ProblemCatalog;
import dev.codearena.problem.domain.ProblemDoc;
import dev.codearena.problem.domain.ProblemRecord;
import dev.codearena.problem.domain.ProblemRepository;
import dev.codearena.problem.domain.TestStore;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * ADMIN only (checked by the gateway and again here).
 * Lifecycle: DRAFT / IN_REVIEW  --publish-->  PUBLISHED  --retire-->  RETIRED;  IN_REVIEW --reject--> REJECTED
 */
@RestController
@RequestMapping("/v1/admin/problems")
@PreAuthorize("hasRole('ADMIN')")
class AdminProblemController {
    private final ProblemRepository repo;
    private final TestStore tests;
    private final ProblemCatalog catalog;
    private final CodeGenerator codegen;
    private final AuditLog audit;

    AdminProblemController(ProblemRepository repo, TestStore tests, ProblemCatalog catalog, CodeGenerator codegen,
                           AuditLog audit) {
        this.repo = repo;
        this.tests = tests;
        this.catalog = catalog;
        this.codegen = codegen;
        this.audit = audit;
    }

    record AdminRow(String slug, String title, String difficulty, String status, int number, int testsVersion,
                    String source, String updatedAt) {}

    record AdminDetail(ProblemRecord record, Map<String, String> starters) {}

    @GetMapping
    List<AdminRow> list(@RequestParam(defaultValue = "IN_REVIEW") String status) {
        return repo.byStatus(status).stream()
                .sorted(Comparator.comparing((ProblemRecord r) -> String.valueOf(r.updatedAt())).reversed())
                .map(r -> new AdminRow(r.doc().slug(), r.doc().title(), r.doc().difficulty(), r.status(), r.number(),
                        r.testsVersion(), r.source(), r.updatedAt()))
                .toList();
    }

    @GetMapping("/{slug}")
    AdminDetail get(@PathVariable String slug) {
        ProblemRecord r = find(slug);
        Map<String, String> starters = new java.util.LinkedHashMap<>();
        Arrays.stream(Lang.values()).forEach(l -> starters.put(l.id, codegen.starter(l, r.doc().signature())));
        return new AdminDetail(r, starters);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    void create(@Valid @RequestBody ProblemDoc doc, @AuthenticationPrincipal Jwt admin) {
        doc.signature().validate();
        repo.create(doc, "DRAFT", "manual:" + admin.getSubject());
        audit.record(admin.getSubject(), "CREATE_PROBLEM", doc.slug());
    }

    @PutMapping("/{slug}")
    void update(@PathVariable String slug, @Valid @RequestBody ProblemDoc doc, @AuthenticationPrincipal Jwt admin) {
        if (!slug.equals(doc.slug())) throw ApiException.badRequest("SLUG_MISMATCH", "slug cannot change");
        doc.signature().validate();
        find(slug);
        repo.updateDoc(doc);
        catalog.invalidate();
        audit.record(admin.getSubject(), "UPDATE_PROBLEM", slug);
    }

    /** Body: JSON Lines, one {"input": {...}, "output": ...} per line. Creates a new tests version. */
    @PutMapping(value = "/{slug}/tests", consumes = {"application/x-ndjson", "text/plain"})
    Map<String, Integer> uploadTests(@PathVariable String slug, @RequestBody String jsonl, @AuthenticationPrincipal Jwt admin) {
        ProblemRecord r = find(slug);
        int version = r.testsVersion() + 1;
        int count = tests.upload(slug, version, jsonl, r.doc().signature().params().stream().map(p -> p.name()).toList());
        repo.setTestsVersion(slug, version);
        audit.record(admin.getSubject(), "UPLOAD_TESTS", slug + "@v" + version);
        return Map.of("testsVersion", version, "cases", count);
    }

    @PostMapping("/{slug}/publish")
    Map<String, Integer> publish(@PathVariable String slug, @AuthenticationPrincipal Jwt admin) {
        ProblemRecord r = find(slug);
        if (r.published()) throw ApiException.conflict("ALREADY_PUBLISHED", "already published as #" + r.number());
        if (!tests.exists(slug, r.testsVersion()))
            throw ApiException.badRequest("NO_TESTS", "upload hidden test cases before publishing");
        int number = repo.publish(slug);
        catalog.invalidate();
        audit.record(admin.getSubject(), "PUBLISH_PROBLEM", slug + "#" + number);
        return Map.of("number", number);
    }

    @PostMapping("/{slug}/reject")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void reject(@PathVariable String slug, @AuthenticationPrincipal Jwt admin) {
        find(slug);
        repo.setStatus(slug, "REJECTED");
        audit.record(admin.getSubject(), "REJECT_PROBLEM", slug);
    }

    @PostMapping("/{slug}/retire")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void retire(@PathVariable String slug, @AuthenticationPrincipal Jwt admin) {
        find(slug);
        repo.retire(slug);
        catalog.invalidate();
        audit.record(admin.getSubject(), "RETIRE_PROBLEM", slug);
    }

    private ProblemRecord find(String slug) {
        return repo.find(slug).orElseThrow(() -> ApiException.notFound("problem"));
    }
}
```

**File:** `services/problem-service/src/main/java/dev/codearena/problem/admin/AuditLog.java`

```java
package dev.codearena.problem.admin;

import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

import java.time.Instant;
import java.util.Map;

import static dev.codearena.common.aws.Ddb.s;

/** Append-only record of admin actions: who, what, when. */
@Component
class AuditLog {
    private final DynamoDbClient ddb;

    AuditLog(DynamoDbClient ddb) {
        this.ddb = ddb;
    }

    void record(String actor, String action, String target) {
        String now = Instant.now().toString();
        ddb.putItem(r -> r.tableName("AuditLog").item(Map.of(
                "PK", s("AUDIT#" + now.substring(0, 10)), "SK", s(now + "#" + actor),
                "actor", s(actor), "action", s(action), "target", s(target))));
    }
}
```

**File:** `services/problem-service/src/main/java/dev/codearena/problem/admin/AdminIngestionController.java`

```java
package dev.codearena.problem.admin;

import com.github.f4b6a3.ulid.UlidCreator;
import dev.codearena.common.events.Queues;
import dev.codearena.common.web.ApiException;
import io.awspring.cloud.sqs.operations.SqsTemplate;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static dev.codearena.common.aws.Ddb.*;

/**
 * ADMIN starts AI + RAG ingestion runs here. Each seed (topic x difficulty) becomes one SQS message on
 * ingestion-jobs; the Python worker (ingestion/) processes it and records its stage on the run.
 *
 * IngestionRuns table:  PK=RUN#<id> SK=META         run parameters and creator
 *                       PK=RUN#<id> SK=SEED#<nnn>   one seed: topic, difficulty, stage, slug, error
 */
@RestController
@RequestMapping("/v1/admin/ingestion")
@PreAuthorize("hasRole('ADMIN')")
class AdminIngestionController {
    static final List<String> DEFAULT_TOPICS = List.of(
            "arrays", "two pointers", "sliding window", "hash map", "prefix sums", "binary search", "sorting",
            "stack", "monotonic stack", "queue", "linked list", "trees", "binary search tree", "heap",
            "graphs BFS", "graphs DFS", "union find", "topological sort", "shortest paths", "dynamic programming 1D",
            "dynamic programming 2D", "knapsack", "greedy", "intervals", "backtracking", "bit manipulation",
            "math", "strings", "tries", "matrix");

    private final DynamoDbClient ddb;
    private final SqsTemplate sqs;
    private final AuditLog audit;

    AdminIngestionController(DynamoDbClient ddb, SqsTemplate sqs, AuditLog audit) {
        this.ddb = ddb;
        this.sqs = sqs;
        this.audit = audit;
    }

    record StartRun(List<String> topics, List<String> difficulties, @Min(1) @Max(200) int count) {}

    record Seed(String id, String topic, String difficulty) {}

    record Job(String runId, Seed seed) {}

    record SeedStatus(String id, String topic, String difficulty, String stage, String slug, String error,
                      String updatedAt) {}

    record RunView(String runId, String createdBy, String createdAt, int total, Map<String, Long> stages,
                   List<SeedStatus> seeds) {}

    @PostMapping("/runs")
    @ResponseStatus(HttpStatus.ACCEPTED)
    Map<String, Object> start(@Valid @RequestBody StartRun req, @AuthenticationPrincipal Jwt admin) {
        List<String> topics = req.topics() == null || req.topics().isEmpty() ? DEFAULT_TOPICS : req.topics();
        List<String> diffs = req.difficulties() == null || req.difficulties().isEmpty()
                ? List.of("EASY", "MEDIUM", "HARD") : req.difficulties();
        for (String d : diffs)
            if (!d.matches("EASY|MEDIUM|HARD")) throw ApiException.badRequest("BAD_DIFFICULTY", d);

        String runId = UlidCreator.getMonotonicUlid().toString();
        String now = Instant.now().toString();
        long ttl = Instant.now().plusSeconds(90L * 24 * 3600).getEpochSecond();
        ddb.putItem(r -> r.tableName("IngestionRuns").item(Map.of(
                "PK", s("RUN#" + runId), "SK", s("META"), "createdBy", s(admin.getSubject()),
                "createdAt", s(now), "total", n(req.count()), "ttl", n(ttl))));

        List<Seed> seeds = new ArrayList<>();
        for (int i = 0; i < req.count(); i++) {
            seeds.add(new Seed(String.format("%03d", i + 1), topics.get(i % topics.size()),
                    diffs.get((i / topics.size()) % diffs.size())));
        }
        for (Seed seed : seeds) {
            ddb.putItem(r -> r.tableName("IngestionRuns").item(Map.of(
                    "PK", s("RUN#" + runId), "SK", s("SEED#" + seed.id()), "topic", s(seed.topic()),
                    "difficulty", s(seed.difficulty()), "stage", s("QUEUED"), "updatedAt", s(now), "ttl", n(ttl))));
            sqs.send(Queues.INGESTION_JOBS, new Job(runId, seed));
        }
        audit.record(admin.getSubject(), "START_INGESTION", runId + " x" + req.count());
        return Map.of("runId", runId, "seeds", seeds.size());
    }

    @GetMapping("/runs")
    List<Map<String, String>> runs() {
        return ddb.scan(r -> r.tableName("IngestionRuns").filterExpression("SK = :m")
                        .expressionAttributeValues(Map.of(":m", s("META"))))
                .items().stream()
                .map(i -> Map.of("runId", str(i, "PK").substring(4), "createdAt", str(i, "createdAt"),
                        "total", String.valueOf(num(i, "total"))))
                .sorted(Comparator.comparing((Map<String, String> m) -> m.get("createdAt")).reversed())
                .limit(50).toList();
    }

    @GetMapping("/runs/{runId}")
    RunView run(@PathVariable String runId) {
        var items = ddb.query(r -> r.tableName("IngestionRuns").keyConditionExpression("PK = :p")
                .expressionAttributeValues(Map.of(":p", s("RUN#" + runId)))).items();
        Map<String, AttributeValue> meta = items.stream().filter(i -> "META".equals(str(i, "SK"))).findFirst()
                .orElseThrow(() -> ApiException.notFound("run"));
        List<SeedStatus> seeds = items.stream().filter(i -> str(i, "SK").startsWith("SEED#"))
                .map(i -> new SeedStatus(str(i, "SK").substring(5), str(i, "topic"), str(i, "difficulty"),
                        str(i, "stage"), str(i, "slug"), str(i, "error"), str(i, "updatedAt")))
                .toList();
        Map<String, Long> stages = new HashMap<>();
        seeds.forEach(s -> stages.merge(s.stage(), 1L, Long::sum));
        return new RunView(runId, str(meta, "createdBy"), str(meta, "createdAt"), (int) num(meta, "total"),
                stages, seeds);
    }
}
```

**File:** `services/problem-service/src/test/java/dev/codearena/problem/ExampleInputsTest.java`

```java
package dev.codearena.problem;

import dev.codearena.codegen.Signature;
import dev.codearena.problem.api.ExampleInputs;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExampleInputsTest {
    private final Signature sig = new Signature("pairWithTargetSum",
            List.of(new Signature.Param("nums", "int[]"), new Signature.Param("target", "int")), "int[]", "JSON");

    @Test
    void convertsNamedExampleToJson() {
        assertThat(ExampleInputs.toJson("nums = [3,1,4,6], target = 7", sig))
                .isEqualTo("{\"nums\":[3,1,4,6],\"target\":7}");
    }

    @Test
    void acceptsPositionalValuesAndStringsWithCommas() {
        var s = new Signature("f", List.of(new Signature.Param("s", "string"), new Signature.Param("k", "int")), "int", "EXACT");
        assertThat(ExampleInputs.toJson("\"a,b\", 2", s)).isEqualTo("{\"s\":\"a,b\",\"k\":2}");
    }

    @Test
    void rejectsWrongArity() {
        assertThatThrownBy(() -> ExampleInputs.toJson("[1,2]", sig)).isInstanceOf(IllegalArgumentException.class);
    }
}
```

## Seed problems

Seven original problems, each in its own folder: `problem.json` (the same JSON the admin API accepts),
`reference.py` (a correct solution, plus a brute force where one is practical) and `gen.py` (a deterministic test
generator). `scripts/seed.py` generates 33 hidden tests per problem (the examples, 6 edge, 20 small, 4 large),
computes expected outputs with the reference, checks that every example in the statement is right, and
cross-checks the reference against brute force on 300 random inputs.

**File:** `seed/problems/balanced-brackets/gen.py`

```python
def gen(rng, size):
    n = {"edge": rng.randint(0, 3), "small": rng.randint(2, 40), "large": rng.randint(50000, 100000)}[size]
    if rng.random() < 0.5:                      # build a balanced string, maybe break it
        out, stack = [], []
        while len(out) + len(stack) < n:
            if stack and rng.random() < 0.5:
                out.append({"(": ")", "[": "]", "{": "}"}[stack.pop()])
            else:
                c = rng.choice("([{")
                stack.append(c)
                out.append(c)
        while stack:
            out.append({"(": ")", "[": "]", "{": "}"}[stack.pop()])
        if out and rng.random() < 0.4:
            k = rng.randrange(len(out))
            out[k] = rng.choice("()[]{}")
        return {"s": "".join(out)}
    return {"s": "".join(rng.choice("()[]{}") for _ in range(n))}
```

**File:** `seed/problems/balanced-brackets/problem.json`

```json
{
  "slug": "balanced-brackets",
  "title": "Balanced Brackets",
  "difficulty": "EASY",
  "tags": ["stack", "strings"],
  "statement": "Given a string `s` made only of the characters `(`, `)`, `[`, `]`, `{` and `}`, decide whether it is **balanced**:\n\n- every opening bracket is closed by a bracket of the same type, and\n- brackets close in the correct order.\n\nThe empty string is balanced.",
  "constraints": ["0 <= s.length <= 10^5", "s contains only ()[]{}"],
  "examples": [
    {"input": "s = \"([]{})\"", "output": "true"},
    {"input": "s = \"([)]\"", "output": "false", "explanation": "] closes ( before ) closes ["},
    {"input": "s = \"((\"", "output": "false"}
  ],
  "signature": {
    "function": "isBalanced",
    "params": [{"name": "s", "type": "string"}],
    "returns": "bool",
    "compare": "JSON"
  },
  "limits": {"timeMs": 2000, "memoryMb": 256},
  "hints": ["Push opening brackets; a closing bracket must match the top of the stack."]
}
```

**File:** `seed/problems/balanced-brackets/reference.py`

```python
def solve(s):
    pairs = {")": "(", "]": "[", "}": "{"}
    stack = []
    for c in s:
        if c in "([{":
            stack.append(c)
        elif not stack or stack.pop() != pairs[c]:
            return False
    return not stack
```

**File:** `seed/problems/count-islands/gen.py`

```python
def gen(rng, size):
    r, c = {"edge": (rng.randint(1, 2), rng.randint(1, 2)),
            "small": (rng.randint(2, 12), rng.randint(2, 12)),
            "large": (rng.randint(200, 300), rng.randint(200, 300))}[size]
    p = rng.choice([0.2, 0.45, 0.6])
    return {"grid": [[1 if rng.random() < p else 0 for _ in range(c)] for _ in range(r)]}
```

**File:** `seed/problems/count-islands/problem.json`

```json
{
  "slug": "count-islands",
  "title": "Count the Islands",
  "difficulty": "MEDIUM",
  "tags": ["graphs", "matrix", "bfs"],
  "statement": "A map is given as a grid of `0`s (water) and `1`s (land). An **island** is a group of land cells connected horizontally or vertically (not diagonally).\n\nReturn the number of islands in `grid`.",
  "constraints": ["1 <= rows, cols <= 300", "grid[r][c] is 0 or 1"],
  "examples": [
    {"input": "grid = [[1,1,0,0],[1,0,0,1],[0,0,1,1]]", "output": "2"},
    {"input": "grid = [[1,0,1],[0,1,0],[1,0,1]]", "output": "5", "explanation": "Diagonal neighbours are not connected."},
    {"input": "grid = [[0]]", "output": "0"}
  ],
  "signature": {
    "function": "countIslands",
    "params": [{"name": "grid", "type": "int[][]"}],
    "returns": "int",
    "compare": "JSON"
  },
  "limits": {"timeMs": 3000, "memoryMb": 256},
  "hints": ["Each time you meet unvisited land, flood-fill its whole island with BFS and count one."]
}
```

**File:** `seed/problems/count-islands/reference.py`

```python
from collections import deque


def solve(grid):
    rows, cols = len(grid), len(grid[0])
    seen = [[False] * cols for _ in range(rows)]
    count = 0
    for r in range(rows):
        for c in range(cols):
            if grid[r][c] == 1 and not seen[r][c]:
                count += 1
                seen[r][c] = True
                q = deque([(r, c)])
                while q:
                    y, x = q.popleft()
                    for ny, nx in ((y + 1, x), (y - 1, x), (y, x + 1), (y, x - 1)):
                        if 0 <= ny < rows and 0 <= nx < cols and grid[ny][nx] == 1 and not seen[ny][nx]:
                            seen[ny][nx] = True
                            q.append((ny, nx))
    return count
```

**File:** `seed/problems/longest-unique-substring/gen.py`

```python
import string


def gen(rng, size):
    n = {"edge": rng.randint(0, 3), "small": rng.randint(4, 60), "large": rng.randint(50000, 100000)}[size]
    alphabet = string.ascii_lowercase[: rng.choice([2, 5, 26])]
    return {"s": "".join(rng.choice(alphabet) for _ in range(n))}
```

**File:** `seed/problems/longest-unique-substring/problem.json`

```json
{
  "slug": "longest-unique-substring",
  "title": "Longest Run Without Repeats",
  "difficulty": "MEDIUM",
  "tags": [
    "strings",
    "sliding-window",
    "hash-map"
  ],
  "statement": "Given a string `s` of lowercase English letters, return the length of the longest **contiguous** substring in which no letter appears twice.",
  "constraints": [
    "0 <= s.length <= 10^5",
    "s contains only a-z"
  ],
  "examples": [
    {
      "input": "s = \"pqrpqs\"",
      "output": "4",
      "explanation": "\"rpqs\" has no repeated letter."
    },
    {
      "input": "s = \"zzzz\"",
      "output": "1"
    },
    {
      "input": "s = \"\"",
      "output": "0"
    }
  ],
  "signature": {
    "function": "longestUniqueRun",
    "params": [
      {
        "name": "s",
        "type": "string"
      }
    ],
    "returns": "int",
    "compare": "JSON"
  },
  "limits": {
    "timeMs": 2000,
    "memoryMb": 256
  },
  "hints": [
    "Keep a window [left, right] with no repeats and the last index of every letter."
  ]
}
```

**File:** `seed/problems/longest-unique-substring/reference.py`

```python
def solve(s):
    last, left, best = {}, 0, 0
    for right, ch in enumerate(s):
        if last.get(ch, -1) >= left:
            left = last[ch] + 1
        last[ch] = right
        best = max(best, right - left + 1)
    return best


def brute(s):
    best = 0
    for i in range(len(s)):
        for j in range(i, len(s)):
            if len(set(s[i:j + 1])) == j - i + 1:
                best = max(best, j - i + 1)
    return best
```

**File:** `seed/problems/max-subarray-sum/gen.py`

```python
def gen(rng, size):
    n = {"edge": rng.randint(1, 3), "small": rng.randint(4, 60), "large": rng.randint(100000, 200000)}[size]
    lim = 10**9 if rng.random() < 0.5 else 100
    return {"nums": [rng.randint(-lim, lim) for _ in range(n)]}
```

**File:** `seed/problems/max-subarray-sum/problem.json`

```json
{
  "slug": "max-subarray-sum",
  "title": "Maximum Subarray Sum",
  "difficulty": "MEDIUM",
  "tags": [
    "arrays",
    "dynamic-programming"
  ],
  "statement": "Given a non-empty integer array `nums`, return the largest sum of any **non-empty contiguous** subarray.\n\nThe answer can exceed the 32-bit range, so return a 64-bit integer.",
  "constraints": [
    "1 <= nums.length <= 2 * 10^5",
    "-10^9 <= nums[i] <= 10^9"
  ],
  "examples": [
    {
      "input": "nums = [4,-6,5,-1,3,-8,2]",
      "output": "7",
      "explanation": "[5,-1,3] has sum 7."
    },
    {
      "input": "nums = [-7]",
      "output": "-7"
    },
    {
      "input": "nums = [1000000000,1000000000,1000000000]",
      "output": "3000000000"
    }
  ],
  "signature": {
    "function": "maxSubarraySum",
    "params": [
      {
        "name": "nums",
        "type": "int[]"
      }
    ],
    "returns": "long",
    "compare": "JSON"
  },
  "limits": {
    "timeMs": 2000,
    "memoryMb": 256
  },
  "hints": [
    "The best subarray ending at index i either extends the best one ending at i-1 or starts fresh at i."
  ]
}
```

**File:** `seed/problems/max-subarray-sum/reference.py`

```python
def solve(nums):
    best = cur = nums[0]
    for x in nums[1:]:
        cur = max(x, cur + x)
        best = max(best, cur)
    return best


def brute(nums):
    return max(sum(nums[i:j + 1]) for i in range(len(nums)) for j in range(i, len(nums)))
```

**File:** `seed/problems/min-meeting-rooms/gen.py`

```python
def gen(rng, size):
    n = {"edge": rng.randint(1, 3), "small": rng.randint(4, 40), "large": rng.randint(50000, 100000)}[size]
    span = 50 if size != "large" else 10**9
    out = []
    for _ in range(n):
        s = rng.randint(0, span - 1)
        out.append([s, rng.randint(s + 1, min(span, s + max(2, span // 5)))])
    return {"meetings": out}
```

**File:** `seed/problems/min-meeting-rooms/problem.json`

```json
{
  "slug": "min-meeting-rooms",
  "title": "Fewest Meeting Rooms",
  "difficulty": "MEDIUM",
  "tags": [
    "intervals",
    "sorting",
    "heap"
  ],
  "statement": "Each meeting is given as `[start, end]` with `start < end`; a meeting occupies a room for the half-open time range `[start, end)`. A meeting that ends at time `t` frees its room for a meeting that starts at `t`.\n\nReturn the minimum number of rooms needed to hold all `meetings`.",
  "constraints": [
    "1 <= meetings.length <= 10^5",
    "0 <= start < end <= 10^9"
  ],
  "examples": [
    {
      "input": "meetings = [[2,12],[4,6],[7,9]]",
      "output": "2",
      "explanation": "[4,6] and [7,9] can share one room while [2,12] uses another."
    },
    {
      "input": "meetings = [[1,5],[5,9]]",
      "output": "1",
      "explanation": "The second meeting starts exactly when the first ends."
    },
    {
      "input": "meetings = [[1,4],[2,5],[3,6]]",
      "output": "3"
    }
  ],
  "signature": {
    "function": "minRooms",
    "params": [
      {
        "name": "meetings",
        "type": "int[][]"
      }
    ],
    "returns": "int",
    "compare": "JSON"
  },
  "limits": {
    "timeMs": 2000,
    "memoryMb": 256
  },
  "hints": [
    "Sort by start; keep a min-heap of end times of meetings in progress."
  ]
}
```

**File:** `seed/problems/min-meeting-rooms/reference.py`

```python
import heapq


def solve(meetings):
    ends = []
    for s, e in sorted(meetings):
        if ends and ends[0] <= s:
            heapq.heapreplace(ends, e)
        else:
            heapq.heappush(ends, e)
    return len(ends)


def brute(meetings):
    points = sorted({p for m in meetings for p in m})
    return max(sum(1 for s, e in meetings if s <= t < e) for t in points)
```

**File:** `seed/problems/pair-with-target-sum/gen.py`

```python
def gen(rng, size):
    n = {"edge": rng.randint(2, 4), "small": rng.randint(5, 50), "large": rng.randint(20000, 100000)}[size]
    lim = 10 if size == "edge" else 10**9
    nums = [rng.randint(-lim, lim) for _ in range(n)]
    i, j = sorted(rng.sample(range(n), 2))
    return {"nums": nums, "target": nums[i] + nums[j]}
```

**File:** `seed/problems/pair-with-target-sum/problem.json`

```json
{
  "slug": "pair-with-target-sum",
  "title": "Pair With Target Sum",
  "difficulty": "EASY",
  "tags": ["arrays", "hash-map"],
  "statement": "You are given an integer array `nums` and an integer `target`.\n\nReturn the indices `[i, j]` (with `i < j`) of two elements whose sum is exactly `target`.\n\nIf several pairs exist, return the pair with the **smallest `j`**; if several pairs share that `j`, return the one with the **smallest `i`**. It is guaranteed that at least one pair exists.",
  "constraints": ["2 <= nums.length <= 10^5", "-10^9 <= nums[i] <= 10^9", "-2 * 10^9 <= target <= 2 * 10^9"],
  "examples": [
    {"input": "nums = [3,1,4,6], target = 7", "output": "[0,2]", "explanation": "nums[0] + nums[2] = 3 + 4 = 7. Pair [1,3] also works, but its j is larger."},
    {"input": "nums = [5,5], target = 10", "output": "[0,1]"},
    {"input": "nums = [2,7,2,7], target = 9", "output": "[0,1]"}
  ],
  "signature": {
    "function": "pairWithTargetSum",
    "params": [{"name": "nums", "type": "int[]"}, {"name": "target", "type": "int"}],
    "returns": "int[]",
    "compare": "JSON"
  },
  "limits": {"timeMs": 2000, "memoryMb": 256},
  "hints": ["For each j, you only need to know whether target - nums[j] appeared before, and where it first appeared."]
}
```

**File:** `seed/problems/pair-with-target-sum/reference.py`

```python
def solve(nums, target):
    first = {}
    for j, x in enumerate(nums):
        if target - x in first:
            return [first[target - x], j]
        first.setdefault(x, j)
    raise ValueError("no pair")


def brute(nums, target):
    for j in range(len(nums)):
        for i in range(j):
            if nums[i] + nums[j] == target:
                return [i, j]
```

**File:** `seed/problems/shortest-path-with-breaks/gen.py`

```python
def gen(rng, size):
    r, c = {"edge": (rng.randint(1, 2), rng.randint(1, 3)),
            "small": (rng.randint(2, 8), rng.randint(2, 8)),
            "large": (rng.randint(30, 40), rng.randint(30, 40))}[size]
    p = rng.choice([0.2, 0.35, 0.5])
    grid = [[1 if rng.random() < p else 0 for _ in range(c)] for _ in range(r)]
    grid[0][0] = 0
    grid[r - 1][c - 1] = 0
    return {"grid": grid, "k": rng.randint(0, max(0, (r + c) // 3))}
```

**File:** `seed/problems/shortest-path-with-breaks/problem.json`

```json
{
  "slug": "shortest-path-with-breaks",
  "title": "Shortest Path With Wall Breaks",
  "difficulty": "HARD",
  "tags": [
    "graphs",
    "bfs",
    "matrix"
  ],
  "statement": "You are in the top-left cell of `grid` and want to reach the bottom-right cell. `0` is an open cell and `1` is a wall. Each step moves one cell up, down, left or right.\n\nYou may break through **at most `k` walls** along the way (walking into a wall cell breaks it).\n\nReturn the minimum number of steps, or `-1` if the target cannot be reached. The start and target cells are always open.",
  "constraints": [
    "1 <= rows, cols <= 40",
    "0 <= k <= rows * cols",
    "grid[0][0] == grid[rows-1][cols-1] == 0"
  ],
  "examples": [
    {
      "input": "grid = [[0,1,0],[1,1,0],[0,0,0]], k = 1",
      "output": "4",
      "explanation": "Break the wall at (0,1), then go right and down."
    },
    {
      "input": "grid = [[0,1,0],[1,1,0],[0,0,0]], k = 0",
      "output": "-1",
      "explanation": "Both neighbours of the start are walls."
    },
    {
      "input": "grid = [[0]], k = 0",
      "output": "0"
    }
  ],
  "signature": {
    "function": "shortestPath",
    "params": [
      {
        "name": "grid",
        "type": "int[][]"
      },
      {
        "name": "k",
        "type": "int"
      }
    ],
    "returns": "int",
    "compare": "JSON"
  },
  "limits": {
    "timeMs": 3000,
    "memoryMb": 256
  },
  "hints": [
    "BFS over states (row, col, walls broken so far).",
    "Breaking more than rows + cols - 3 walls is never needed."
  ]
}
```

**File:** `seed/problems/shortest-path-with-breaks/reference.py`

```python
from collections import deque


def solve(grid, k):
    rows, cols = len(grid), len(grid[0])
    if rows == 1 and cols == 1:
        return 0
    k = min(k, rows + cols - 3) if rows + cols > 3 else k
    best = [[-1] * cols for _ in range(rows)]      # most walls left when reaching a cell
    best[0][0] = k
    q = deque([(0, 0, k, 0)])
    while q:
        r, c, left, d = q.popleft()
        for nr, nc in ((r + 1, c), (r - 1, c), (r, c + 1), (r, c - 1)):
            if 0 <= nr < rows and 0 <= nc < cols:
                nl = left - grid[nr][nc]
                if nl < 0 or nl <= best[nr][nc]:
                    continue
                if nr == rows - 1 and nc == cols - 1:
                    return d + 1
                best[nr][nc] = nl
                q.append((nr, nc, nl, d + 1))
    return -1


def brute(grid, k):
    rows, cols = len(grid), len(grid[0])
    seen = {(0, 0, 0)}
    q = deque([(0, 0, 0, 0)])
    while q:
        r, c, used, d = q.popleft()
        if (r, c) == (rows - 1, cols - 1):
            return d
        for nr, nc in ((r + 1, c), (r - 1, c), (r, c + 1), (r, c - 1)):
            if 0 <= nr < rows and 0 <= nc < cols:
                nu = used + grid[nr][nc]
                if nu <= k and (nr, nc, nu) not in seen:
                    seen.add((nr, nc, nu))
                    q.append((nr, nc, nu, d + 1))
    return -1
```

**File:** `scripts/seed.py`

```python
#!/usr/bin/env python3
"""
Seed the local stack with the original problems in seed/problems/<slug>/:
  problem.json   the problem (same JSON as the admin API's ProblemDoc)
  reference.py   solve(**inputs) -> expected output   (and optionally brute(**inputs) for cross-checks)
  gen.py         gen(rng, size) -> inputs dict, size in {"edge", "small", "large"}

For each problem: generate deterministic hidden tests, compute outputs with the reference solution,
check the statement's examples against the reference, upload tests to S3 and publish to DynamoDB.

    scripts/py.sh scripts/seed.py              # seed (skips problems that already exist)
    scripts/py.sh scripts/seed.py --check      # verify only: examples + reference vs brute force; no AWS needed
    scripts/py.sh scripts/seed.py --force      # re-upload tests and docs of existing problems
    scripts/py.sh scripts/seed.py --demo 250   # also add 250 demo problems to try pagination (100 per page)
"""
import argparse
import importlib.util
import json
import os
import random
import sys
import time
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
PROBLEMS = ROOT / "seed" / "problems"
CASES = [("edge", 6), ("small", 20), ("large", 4)]


def load_module(path: Path, name: str):
    spec = importlib.util.spec_from_file_location(name, path)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


def split_top_level(s: str):
    """Same rules as ExampleInputs.java: split on commas outside brackets and quotes."""
    out, depth, in_str, cur = [], 0, False, []
    for i, c in enumerate(s):
        if c == '"' and (i == 0 or s[i - 1] != "\\"):
            in_str = not in_str
        if not in_str:
            if c in "[{":
                depth += 1
            elif c in "]}":
                depth -= 1
            elif c == "," and depth == 0:
                out.append("".join(cur))
                cur = []
                continue
        cur.append(c)
    if "".join(cur).strip():
        out.append("".join(cur))
    return out


def example_to_input(text: str, params):
    parts = split_top_level(text)
    if len(parts) != len(params):
        raise ValueError(f"example needs {len(params)} values: {text}")
    values = {}
    for part, p in zip(parts, params):
        name, eq, value = part.partition("=")
        values[p["name"]] = json.loads(value.strip() if eq else part.strip())
    return values


def build_cases(slug: str, problem: dict, ref, gen_mod):
    rng = random.Random(slug)                         # deterministic per problem
    params = problem["signature"]["params"]
    inputs = [example_to_input(e["input"], params) for e in problem["examples"]]
    for size, count in CASES:
        inputs += [gen_mod.gen(rng, size) for _ in range(count)]
    cases = [{"input": i, "output": ref.solve(**json.loads(json.dumps(i)))} for i in inputs]
    for e, c in zip(problem["examples"], cases):
        if json.loads(e["output"]) != c["output"]:
            raise ValueError(f"{slug}: example {e['input']} says {e['output']}, reference gives {c['output']}")
    return cases


def check(slug, problem, ref, gen_mod):
    cases = build_cases(slug, problem, ref, gen_mod)
    msg = f"{slug}: {len(cases)} cases, examples ok"
    if hasattr(ref, "brute"):
        rng = random.Random(slug + "-brute")
        for _ in range(300):
            i = gen_mod.gen(rng, rng.choice(["edge", "small"]))
            a, b = ref.solve(**json.loads(json.dumps(i))), ref.brute(**json.loads(json.dumps(i)))
            if a != b:
                raise ValueError(f"{slug}: reference {a} != brute {b} for {i}")
        msg += ", 300 random cases match brute force"
    print(msg)
    return cases


def aws_clients():
    import boto3
    region = os.environ.get("AWS_REGION", "ap-south-1")
    creds = dict(aws_access_key_id=os.environ.get("AWS_ACCESS_KEY_ID", "local"),
                 aws_secret_access_key=os.environ.get("AWS_SECRET_ACCESS_KEY", "local"), region_name=region)
    return (boto3.client("dynamodb", endpoint_url=os.environ.get("DYNAMO_ENDPOINT", "http://localhost:9700"), **creds),
            boto3.client("s3", endpoint_url=os.environ.get("S3_ENDPOINT", "http://localhost:9703"), **creds))


def doc_of(problem: dict) -> dict:
    keys = ["slug", "title", "difficulty", "tags", "statement", "constraints", "examples", "signature", "limits", "hints"]
    return {k: problem.get(k) for k in keys if k in problem}


def publish(ddb, s3, problem: dict, cases, force: bool, source="seed"):
    slug = problem["slug"]
    key = {"PK": {"S": f"PROBLEM#{slug}"}, "SK": {"S": "META"}}
    existing = ddb.get_item(TableName="Problems", Key=key).get("Item")
    if existing and not force:
        print(f"  {slug}: exists, skipped (use --force to overwrite)")
        return
    version = int(existing["testsVersion"]["N"]) + 1 if existing else 1
    body = "\n".join(json.dumps(c, separators=(",", ":")) for c in cases) + "\n"
    s3.put_object(Bucket="codearena-tests", Key=f"{slug}/v{version}/cases.jsonl", Body=body.encode())
    now = datetime.now(timezone.utc).isoformat()
    if existing:
        ddb.update_item(TableName="Problems", Key=key,
                        UpdateExpression="SET #d = :d, title = :t, difficulty = :f, tags = :g, testsVersion = :v, updatedAt = :u",
                        ExpressionAttributeNames={"#d": "doc"},
                        ExpressionAttributeValues={":d": {"S": json.dumps(doc_of(problem))}, ":t": {"S": problem["title"]},
                                                   ":f": {"S": problem["difficulty"]}, ":g": {"S": ",".join(problem["tags"])},
                                                   ":v": {"N": str(version)}, ":u": {"S": now}})
        print(f"  {slug}: updated, tests v{version} ({len(cases)} cases)")
        return
    number = int(ddb.update_item(TableName="Problems", Key={"PK": {"S": "COUNTER"}, "SK": {"S": "PROBLEM_NUMBER"}},
                                 UpdateExpression="ADD #v :one", ExpressionAttributeNames={"#v": "value"},
                                 ExpressionAttributeValues={":one": {"N": "1"}},
                                 ReturnValues="UPDATED_NEW")["Attributes"]["value"]["N"])
    ddb.put_item(TableName="Problems", Item={
        **key, "slug": {"S": slug}, "title": {"S": problem["title"]}, "difficulty": {"S": problem["difficulty"]},
        "tags": {"S": ",".join(problem["tags"])}, "doc": {"S": json.dumps(doc_of(problem))},
        "status": {"S": "PUBLISHED"}, "testsVersion": {"N": str(version)}, "source": {"S": source},
        "number": {"S": f"{number:05d}"}, "listPk": {"S": "LIST#PUBLISHED"},
        "createdAt": {"S": now}, "updatedAt": {"S": now}})
    print(f"  {slug}: published as #{number} ({len(cases)} cases)")


def bump_cache_version():
    """Tell problem-service its cached list is stale (it reads problems:list:version)."""
    try:
        import socket
        host, port = os.environ.get("REDIS_HOST", "localhost"), int(os.environ.get("REDIS_PORT", "9701"))
        with socket.create_connection((host, port), timeout=2) as s:
            s.sendall(b"*2\r\n$4\r\nINCR\r\n$21\r\nproblems:list:version\r\n")
            s.recv(64)
    except OSError as e:
        print(f"(could not reach Redis to refresh the list cache: {e}; it refreshes within 10 minutes)")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--check", action="store_true", help="verify only, write nothing")
    ap.add_argument("--force", action="store_true", help="overwrite existing problems")
    ap.add_argument("--demo", type=int, default=0, help="add N demo problems for pagination tests")
    args = ap.parse_args()

    loaded = []
    for d in sorted(p for p in PROBLEMS.iterdir() if p.is_dir()):
        problem = json.loads((d / "problem.json").read_text())
        ref = load_module(d / "reference.py", f"ref_{d.name.replace('-', '_')}")
        gen_mod = load_module(d / "gen.py", f"gen_{d.name.replace('-', '_')}")
        t0 = time.time()
        cases = check(d.name, problem, ref, gen_mod)
        loaded.append((problem, cases))
        if time.time() - t0 > 30:
            print(f"  (slow: {time.time() - t0:.0f}s)")
    if args.check:
        print("check passed")
        return

    ddb, s3 = aws_clients()
    for problem, cases in loaded:
        publish(ddb, s3, problem, cases, args.force)
    if args.demo:
        base, base_cases = next((p, c) for p, c in loaded if p["slug"] == "pair-with-target-sum")
        for i in range(1, args.demo + 1):
            demo = dict(base, slug=f"demo-problem-{i:04d}", title=f"Demo Problem {i:04d}", tags=["demo"],
                        difficulty=["EASY", "MEDIUM", "HARD"][i % 3])
            publish(ddb, s3, demo, base_cases, args.force, source="demo")
    bump_cache_version()
    print("seed complete")


if __name__ == "__main__":
    sys.exit(main())
```

## Check

Re-enable the seed lines in `scripts/up.sh` if you commented them out, then:

```bash
scripts/py.sh scripts/seed.py --check     # no AWS needed: all 7 problems verified
scripts/up.sh                             # seeds: "published as #1" ... "#7"
./gradlew :services:problem-service:bootJar && java -jar services/problem-service/build/libs/problem-service.jar
curl -s 'localhost:9082/v1/problems?page=1&size=100' | python3 -m json.tool | head -20
curl -s localhost:9082/v1/problems/count-islands/starter/cpp
scripts/py.sh scripts/seed.py --demo 250  # optional: 250 demo problems, to see three pages of 100
```
