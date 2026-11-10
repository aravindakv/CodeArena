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