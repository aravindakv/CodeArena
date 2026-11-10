package dev.codearena.analytics;

import dev.codearena.common.events.Queues;
import dev.codearena.common.events.VerdictReady;
import io.awspring.cloud.sqs.annotation.SqsListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;

import static dev.codearena.common.aws.Ddb.*;

/**
 * Consumes verdict events and keeps pre-aggregated stats, so the profile page is one or two reads at the
 * next login. Counters use DynamoDB ADD (atomic, safe with many concurrent consumers).
 *
 * UserStats table   PK=USER#<id> SK=STATS        totals; attributes "lang#<id>", "solved#<difficulty>", "tag#<tag>"
 *                   PK=USER#<id> SK=DAY#<date>   submissions / accepted per day (heatmap, streaks)
 * UserSolved table  PK=USER#<id> SK=PROBLEM#<slug>  first accepted time (the "solved" tick)
 * Problems table    PROBLEM#<slug>/META  submits, accepts  (acceptance rate shown in the problem list)
 */
@Component
class StatsUpdater {
    private final DynamoDbClient ddb;
    private final StringRedisTemplate redis;
    private final ZoneId zone;

    StatsUpdater(DynamoDbClient ddb, StringRedisTemplate redis, @Value("${codearena.stats-zone}") String zone) {
        this.ddb = ddb;
        this.redis = redis;
        this.zone = ZoneId.of(zone);
    }

    @SqsListener(value = Queues.VERDICT_EVENTS, maxConcurrentMessages = "16")
    void onVerdict(VerdictReady v) {
        if (!"SUBMIT".equals(v.mode()) || "SYSTEM_ERROR".equals(v.verdict())) return;
        // SQS delivers at least once: count each submission once.
        if (!Boolean.TRUE.equals(redis.opsForValue().setIfAbsent("an:done:" + v.submissionId(), "1", Duration.ofDays(2))))
            return;

        String pk = "USER#" + v.userId();
        boolean firstSolve = false;
        if (v.accepted()) {
            try {
                ddb.putItem(r -> r.tableName("UserSolved").item(Map.of("PK", s(pk), "SK", s("PROBLEM#" + v.slug()),
                                "at", s(Instant.ofEpochMilli(v.submittedAtMs()).toString()), "difficulty", s(v.difficulty())))
                        .conditionExpression("attribute_not_exists(PK)"));
                firstSolve = true;
            } catch (ConditionalCheckFailedException ignored) {
                // solved before
            }
        }

        Map<String, String> names = new HashMap<>();
        Map<String, AttributeValue> values = new HashMap<>();
        values.put(":one", n(1));
        StringBuilder add = new StringBuilder("ADD submissions :one, #lang :one");
        names.put("#lang", "lang#" + v.lang());
        if (v.accepted()) add.append(", accepted :one");
        if (firstSolve) {
            add.append(", solvedTotal :one, #diff :one");
            names.put("#diff", "solved#" + v.difficulty());
            for (int i = 0; v.tags() != null && i < v.tags().size(); i++) {
                add.append(", #t").append(i).append(" :one");
                names.put("#t" + i, "tag#" + v.tags().get(i));
            }
        }
        values.put(":now", s(Instant.now().toString()));
        ddb.updateItem(r -> r.tableName("UserStats").key(key(pk, "STATS"))
                .updateExpression(add + " SET updatedAt = :now")
                .expressionAttributeNames(names).expressionAttributeValues(values));

        String day = LocalDate.ofInstant(Instant.ofEpochMilli(v.submittedAtMs()), zone).toString();
        ddb.updateItem(r -> r.tableName("UserStats").key(key(pk, "DAY#" + day))
                .updateExpression(v.accepted() ? "ADD submissions :one, accepted :one" : "ADD submissions :one")
                .expressionAttributeValues(Map.of(":one", n(1))));

        try {   // acceptance rate of the problem
            ddb.updateItem(r -> r.tableName("Problems").key(key("PROBLEM#" + v.slug(), "META"))
                    .updateExpression(v.accepted() ? "ADD submits :one, accepts :one" : "ADD submits :one")
                    .conditionExpression("attribute_exists(PK)")
                    .expressionAttributeValues(Map.of(":one", n(1))));
        } catch (ConditionalCheckFailedException ignored) {
            // problem deleted
        }
        redis.delete("stats:" + v.userId());
    }
}