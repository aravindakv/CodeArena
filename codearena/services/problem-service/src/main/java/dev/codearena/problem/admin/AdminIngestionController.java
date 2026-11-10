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