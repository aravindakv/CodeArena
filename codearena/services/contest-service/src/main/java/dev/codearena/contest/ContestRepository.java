package dev.codearena.contest;

import org.springframework.stereotype.Repository;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.WriteRequest;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static dev.codearena.common.aws.Ddb.*;

/**
 * Contests table
 *   PK=CONTEST#<id> SK=META           the contest
 *   PK=CONTEST#<id> SK=REG#<userId>   a registration
 *   PK=CONTEST#<id> SK=RANK#<00001>   final standings, written once the contest is finalized
 */
@Repository
class ContestRepository {
    private static final String TABLE = "Contests";
    private final DynamoDbClient ddb;

    ContestRepository(DynamoDbClient ddb) {
        this.ddb = ddb;
    }

    void create(Contest c) {
        ddb.putItem(r -> r.tableName(TABLE).item(Map.of(
                "PK", s("CONTEST#" + c.id()), "SK", s("META"), "title", s(c.title()),
                "description", s(c.description() == null ? "" : c.description()),
                "startAt", s(c.startAt().toString()), "endAt", s(c.endAt().toString()),
                "problems", s(String.join(",", c.problems())), "frozen", AttributeValue.fromBool(false),
                "finalized", AttributeValue.fromBool(false), "createdBy", s(c.createdBy()))));
    }

    Optional<Contest> find(String id) {
        var item = ddb.getItem(r -> r.tableName(TABLE).key(key("CONTEST#" + id, "META"))).item();
        return has(item) ? Optional.of(map(item)) : Optional.empty();
    }

    List<Contest> all() {
        return ddb.scan(r -> r.tableName(TABLE).filterExpression("SK = :m")
                        .expressionAttributeValues(Map.of(":m", s("META"))))
                .items().stream().map(ContestRepository::map)
                .sorted(Comparator.comparing(Contest::startAt).reversed()).toList();
    }

    void setFlag(String id, String flag, boolean value) {
        ddb.updateItem(r -> r.tableName(TABLE).key(key("CONTEST#" + id, "META"))
                .updateExpression("SET #f = :v").expressionAttributeNames(Map.of("#f", flag))
                .expressionAttributeValues(Map.of(":v", AttributeValue.fromBool(value))));
    }

    void register(String id, String userId, String displayName) {
        ddb.putItem(r -> r.tableName(TABLE).item(Map.of(
                "PK", s("CONTEST#" + id), "SK", s("REG#" + userId), "displayName", s(displayName),
                "registeredAt", s(Instant.now().toString()))));
    }

    record Standing(int rank, String userId, String name, long solved, long penalty) {}

    void writeStandings(String id, List<Standing> rows) {
        List<WriteRequest> batch = new ArrayList<>();
        for (Standing st : rows) {
            Map<String, AttributeValue> item = new HashMap<>(key("CONTEST#" + id, "RANK#" + String.format("%06d", st.rank())));
            item.put("userId", s(st.userId()));
            item.put("name", s(st.name() == null ? st.userId() : st.name()));
            item.put("solved", n(st.solved()));
            item.put("penalty", n(st.penalty()));
            batch.add(WriteRequest.builder().putRequest(p -> p.item(item)).build());
            if (batch.size() == 25) flush(batch);       // BatchWriteItem takes at most 25 items
        }
        if (!batch.isEmpty()) flush(batch);
    }

    List<Standing> standings(String id, int limit) {
        return ddb.query(r -> r.tableName(TABLE).keyConditionExpression("PK = :p AND begins_with(SK, :r)")
                        .expressionAttributeValues(Map.of(":p", s("CONTEST#" + id), ":r", s("RANK#"))).limit(limit))
                .items().stream()
                .map(i -> new Standing(Integer.parseInt(str(i, "SK").substring(5)), str(i, "userId"), str(i, "name"),
                        num(i, "solved"), num(i, "penalty")))
                .toList();
    }

    private void flush(List<WriteRequest> batch) {
        var res = ddb.batchWriteItem(r -> r.requestItems(Map.of(TABLE, List.copyOf(batch))));
        var unprocessed = res.unprocessedItems();
        while (unprocessed != null && !unprocessed.isEmpty()) {
            var u = unprocessed;
            unprocessed = ddb.batchWriteItem(r -> r.requestItems(u)).unprocessedItems();
        }
        batch.clear();
    }

    private static Contest map(Map<String, AttributeValue> i) {
        return new Contest(str(i, "PK").substring(8), str(i, "title"), str(i, "description"),
                Instant.parse(str(i, "startAt")), Instant.parse(str(i, "endAt")),
                Arrays.stream(str(i, "problems").split(",")).filter(x -> !x.isBlank()).toList(),
                Boolean.TRUE.equals(i.get("frozen").bool()), Boolean.TRUE.equals(i.get("finalized").bool()),
                str(i, "createdBy"));
    }
}