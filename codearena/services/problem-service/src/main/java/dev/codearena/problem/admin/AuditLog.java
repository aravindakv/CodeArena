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