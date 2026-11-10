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