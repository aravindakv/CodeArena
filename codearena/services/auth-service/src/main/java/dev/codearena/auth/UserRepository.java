package dev.codearena.auth;

import dev.codearena.common.web.ApiException;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.Put;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static dev.codearena.common.aws.Ddb.*;

/**
 * Users table:  PK=USER#<id>     SK=PROFILE  -> the user
 *               PK=EMAIL#<email> SK=PROFILE  -> guard item that makes emails unique
 */
@Repository
class UserRepository {
    private static final String TABLE = "Users";
    private final DynamoDbClient ddb;

    UserRepository(DynamoDbClient ddb) {
        this.ddb = ddb;
    }

    record User(String id, String email, String passwordHash, String displayName, Set<String> roles,
                String status, String createdAt) {
        boolean isAdmin() { return roles.contains("ADMIN"); }
    }

    /** Writes the user and the email guard in one transaction; fails if the email is taken. */
    void create(User u) {
        Map<String, AttributeValue> user = new HashMap<>(key("USER#" + u.id(), "PROFILE"));
        user.put("userId", s(u.id()));
        user.put("email", s(u.email()));
        user.put("passwordHash", s(u.passwordHash()));
        user.put("displayName", s(u.displayName()));
        user.put("roles", AttributeValue.fromSs(List.copyOf(u.roles())));
        user.put("status", s(u.status()));
        user.put("createdAt", s(u.createdAt()));
        Map<String, AttributeValue> guard = new HashMap<>(key("EMAIL#" + u.email(), "PROFILE"));
        guard.put("userId", s(u.id()));
        try {
            ddb.transactWriteItems(t -> t.transactItems(
                    TransactWriteItem.builder().put(Put.builder().tableName(TABLE).item(user)
                            .conditionExpression("attribute_not_exists(PK)").build()).build(),
                    TransactWriteItem.builder().put(Put.builder().tableName(TABLE).item(guard)
                            .conditionExpression("attribute_not_exists(PK)").build()).build()));
        } catch (TransactionCanceledException e) {
            throw ApiException.conflict("EMAIL_TAKEN", "an account with this email already exists");
        }
    }

    Optional<User> byId(String id) {
        var item = ddb.getItem(r -> r.tableName(TABLE).key(key("USER#" + id, "PROFILE"))).item();
        return has(item) ? Optional.of(map(item)) : Optional.empty();
    }

    Optional<User> byEmail(String email) {
        var guard = ddb.getItem(r -> r.tableName(TABLE).key(key("EMAIL#" + email, "PROFILE"))).item();
        return has(guard) ? byId(str(guard, "userId")) : Optional.empty();
    }

    void setRoles(String id, Set<String> roles) {
        ddb.updateItem(r -> r.tableName(TABLE).key(key("USER#" + id, "PROFILE"))
                .updateExpression("SET #r = :r")
                .conditionExpression("attribute_exists(PK)")
                .expressionAttributeNames(Map.of("#r", "roles"))
                .expressionAttributeValues(Map.of(":r", AttributeValue.fromSs(List.copyOf(roles)))));
    }

    /** Admin listing. A Scan is fine for local data sizes; add a GSI before you have many users. */
    List<User> list(int limit) {
        return ddb.scan(r -> r.tableName(TABLE).limit(500)
                        .filterExpression("begins_with(PK, :p)")
                        .expressionAttributeValues(Map.of(":p", s("USER#"))))
                .items().stream().limit(limit).map(UserRepository::map).toList();
    }

    void audit(String actorId, String action, String target) {
        String now = Instant.now().toString();
        ddb.putItem(r -> r.tableName("AuditLog").item(Map.of(
                "PK", s("AUDIT#" + now.substring(0, 10)), "SK", s(now + "#" + actorId),
                "actor", s(actorId), "action", s(action), "target", s(target))));
    }

    private static User map(Map<String, AttributeValue> i) {
        return new User(str(i, "userId"), str(i, "email"), str(i, "passwordHash"), str(i, "displayName"),
                Set.copyOf(i.get("roles").ss()), str(i, "status"), str(i, "createdAt"));
    }
}