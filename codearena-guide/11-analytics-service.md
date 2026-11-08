# 11 · Analytics service: stats that are ready at the next login

Analytics never recomputes from raw submissions on a page view. It consumes every verdict event once and keeps
pre-aggregated counters, so the progress page is one item read plus one small query.

| Data | Item | Updated with |
| --- | --- | --- |
| Totals, solved by difficulty, by language, by topic | `UserStats: USER#id / STATS` (flat attributes like `lang#java`) | DynamoDB `ADD` (atomic under concurrency) |
| Activity heatmap and streaks | `UserStats: USER#id / DAY#2026-10-11` | `ADD` per day |
| Solved ticks in the problem list | `UserSolved: USER#id / PROBLEM#slug` | conditional put, so a first solve is counted once |
| Acceptance rate in the problem list | `Problems: PROBLEM#slug / META` `submits`, `accepts` | `ADD` |
| Preferred language and theme | `UserStats: USER#id / PREFS` | put; follows the user to mobile |

Each event is applied once even if SQS delivers it twice (`an:done:<submissionId>` in Redis, 2 days). The stats
response is cached for 30 seconds and evicted when a new verdict arrives. Which calendar day a submission counts on
is set by `STATS_TIME_ZONE` (default Asia/Kolkata).

**File:** `services/analytics-service/build.gradle.kts`

```kotlin
plugins { id("codearena.service") }

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-security-oauth2-resource-server")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("io.awspring.cloud:spring-cloud-aws-starter-dynamodb")
    implementation("io.awspring.cloud:spring-cloud-aws-starter-sqs")
}
```

**File:** `services/analytics-service/src/main/resources/application.yml`

```yaml
server:
  port: ${PORT:9085}
spring:
  application:
    name: analytics-service
  config:
    import: classpath:codearena-common.yml

codearena:
  stats-zone: ${STATS_TIME_ZONE:Asia/Kolkata}    # which calendar day a submission counts on (streaks, heatmap)
```

**File:** `services/analytics-service/src/main/java/dev/codearena/analytics/AnalyticsApplication.java`

```java
package dev.codearena.analytics;

import dev.codearena.common.web.ApiExceptionHandler;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

@SpringBootApplication
@Import(ApiExceptionHandler.class)
public class AnalyticsApplication {
    public static void main(String[] args) {
        SpringApplication.run(AnalyticsApplication.class, args);
    }
}
```

**File:** `services/analytics-service/src/main/java/dev/codearena/analytics/SecurityConfig.java`

```java
package dev.codearena.analytics;

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
                        .requestMatchers("/v1/admin/**").hasRole(Roles.ADMIN)
                        .requestMatchers("/v1/me/**").hasAnyRole(Roles.USER, Roles.ADMIN)
                        .anyRequest().denyAll())
                .oauth2ResourceServer(o -> o.jwt(j -> j.jwtAuthenticationConverter(Roles.jwtConverter())))
                .build();
    }
}
```

**File:** `services/analytics-service/src/main/java/dev/codearena/analytics/StatsUpdater.java`

```java
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
```

**File:** `services/analytics-service/src/main/java/dev/codearena/analytics/StatsController.java`

```java
package dev.codearena.analytics;

import tools.jackson.databind.json.JsonMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.KeysAndAttributes;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static dev.codearena.common.aws.Ddb.*;

@RestController
@RequestMapping("/v1")
class StatsController {
    private final DynamoDbClient ddb;
    private final StringRedisTemplate redis;
    private final JsonMapper json;
    private final ZoneId zone;

    StatsController(DynamoDbClient ddb, StringRedisTemplate redis, JsonMapper json,
                    @Value("${codearena.stats-zone}") String zone) {
        this.ddb = ddb;
        this.redis = redis;
        this.json = json;
        this.zone = ZoneId.of(zone);
    }

    record Stats(long submissions, long accepted, long solvedTotal, Map<String, Long> solvedByDifficulty,
                 Map<String, Long> byLanguage, Map<String, Long> byTag, int currentStreak, int longestStreak,
                 Map<String, Long> heatmap, List<Map<String, String>> recentSolved) {}

    record Prefs(String language, String theme) {}

    /** Everything the profile page needs, cached for 30 s (the updater evicts it on new verdicts). */
    @GetMapping("/me/stats")
    Stats stats(@AuthenticationPrincipal Jwt jwt) throws Exception {
        String userId = jwt.getSubject();
        String cached = redis.opsForValue().get("stats:" + userId);
        if (cached != null) return json.readValue(cached, Stats.class);
        Stats s = compute(userId);
        redis.opsForValue().set("stats:" + userId, json.writeValueAsString(s), Duration.ofSeconds(30));
        return s;
    }

    @GetMapping("/me/solved")
    Set<String> solved(@AuthenticationPrincipal Jwt jwt, @RequestParam List<String> slugs) {
        Set<String> out = new HashSet<>();
        String pk = "USER#" + jwt.getSubject();
        List<String> unique = slugs.stream().distinct().limit(100).toList();   // BatchGetItem: max 100 keys
        if (unique.isEmpty()) return out;
        List<Map<String, AttributeValue>> keys = unique.stream().map(sl -> key(pk, "PROBLEM#" + sl)).toList();
        Map<String, KeysAndAttributes> req = Map.of("UserSolved", KeysAndAttributes.builder().keys(keys).build());
        while (req != null && !req.isEmpty()) {
            var finalReq = req;
            var res = ddb.batchGetItem(r -> r.requestItems(finalReq));
            res.responses().getOrDefault("UserSolved", List.of()).forEach(i -> out.add(str(i, "SK").substring(8)));
            req = res.unprocessedKeys();
        }
        return out;
    }

    @GetMapping("/me/preferences")
    Prefs prefs(@AuthenticationPrincipal Jwt jwt) {
        var i = ddb.getItem(r -> r.tableName("UserStats").key(key("USER#" + jwt.getSubject(), "PREFS"))).item();
        return has(i) ? new Prefs(str(i, "language"), str(i, "theme")) : new Prefs("python", "dark");
    }

    @PutMapping("/me/preferences")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void savePrefs(@AuthenticationPrincipal Jwt jwt, @RequestBody Prefs p) {
        ddb.putItem(r -> r.tableName("UserStats").item(Map.of("PK", s("USER#" + jwt.getSubject()), "SK", s("PREFS"),
                "language", s(p.language() == null ? "python" : p.language()),
                "theme", s(p.theme() == null ? "dark" : p.theme()))));
    }

    /** Platform overview for admins. Scans are fine at local scale; use Athena over exported events on AWS. */
    @GetMapping("/admin/analytics/overview")
    Map<String, Object> overview() {
        long users = 0, submissions = 0, accepted = 0;
        Map<String, Long> langs = new TreeMap<>();
        for (var i : ddb.scanPaginator(r -> r.tableName("UserStats").filterExpression("SK = :s")
                .expressionAttributeValues(Map.of(":s", s("STATS")))).items()) {
            users++;
            submissions += num(i, "submissions");
            accepted += num(i, "accepted");
            i.forEach((k, v) -> { if (k.startsWith("lang#")) langs.merge(k.substring(5), Long.parseLong(v.n()), Long::sum); });
        }
        List<Map<String, Object>> hardest = new ArrayList<>();
        for (var i : ddb.scanPaginator(r -> r.tableName("Problems").filterExpression("SK = :m AND submits > :z")
                .expressionAttributeValues(Map.of(":m", s("META"), ":z", n(0)))).items()) {
            long sub = num(i, "submits"), acc = num(i, "accepts");
            hardest.add(Map.of("slug", str(i, "slug"), "title", str(i, "title"), "submits", sub,
                    "acceptance", Math.round(1000.0 * acc / sub) / 10.0));
        }
        hardest.sort(Comparator.comparingDouble(m -> (Double) m.get("acceptance")));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("activeUsers", users);
        out.put("submissions", submissions);
        out.put("acceptanceRate", submissions == 0 ? 0 : Math.round(1000.0 * accepted / submissions) / 10.0);
        out.put("byLanguage", langs);
        out.put("hardestProblems", hardest.stream().limit(10).toList());
        return out;
    }

    private Stats compute(String userId) {
        String pk = "USER#" + userId;
        var totals = ddb.getItem(r -> r.tableName("UserStats").key(key(pk, "STATS"))).item();
        Map<String, Long> byDiff = new TreeMap<>(), byLang = new TreeMap<>(), byTag = new TreeMap<>();
        if (has(totals)) totals.forEach((k, v) -> {
            if (k.startsWith("solved#")) byDiff.put(k.substring(7), Long.parseLong(v.n()));
            else if (k.startsWith("lang#")) byLang.put(k.substring(5), Long.parseLong(v.n()));
            else if (k.startsWith("tag#")) byTag.put(k.substring(4), Long.parseLong(v.n()));
        });

        LocalDate today = LocalDate.now(zone);
        String from = "DAY#" + today.minusDays(370);
        Map<String, Long> heat = new TreeMap<>();
        for (var i : ddb.queryPaginator(r -> r.tableName("UserStats")
                .keyConditionExpression("PK = :p AND SK BETWEEN :a AND :b")
                .expressionAttributeValues(Map.of(":p", s(pk), ":a", s(from), ":b", s("DAY#9999")))).items()) {
            heat.put(str(i, "SK").substring(4), num(i, "submissions"));
        }
        int[] streaks = streaks(heat.keySet(), today);

        List<Map<String, String>> recent = new ArrayList<>();
        for (var i : ddb.queryPaginator(r -> r.tableName("UserSolved").keyConditionExpression("PK = :p")
                .expressionAttributeValues(Map.of(":p", s(pk)))).items()) {
            recent.add(Map.of("slug", str(i, "SK").substring(8), "at", str(i, "at"),
                    "difficulty", String.valueOf(str(i, "difficulty"))));
        }
        recent.sort(Comparator.comparing((Map<String, String> m) -> m.get("at")).reversed());

        return new Stats(num(totals, "submissions"), num(totals, "accepted"), num(totals, "solvedTotal"),
                byDiff, byLang, byTag, streaks[0], streaks[1], heat, recent.stream().limit(10).toList());
    }

    /** [current, longest] streak of consecutive active days. Current counts if active today or yesterday. */
    static int[] streaks(Set<String> activeDays, LocalDate today) {
        List<LocalDate> days = activeDays.stream().map(LocalDate::parse).sorted().toList();
        int longest = 0, run = 0;
        LocalDate prev = null;
        for (LocalDate d : days) {
            run = prev != null && prev.plusDays(1).equals(d) ? run + 1 : 1;
            longest = Math.max(longest, run);
            prev = d;
        }
        int current = 0;
        if (prev != null && !prev.isBefore(today.minusDays(1))) current = run;
        return new int[]{current, longest};
    }
}
```

**File:** `services/analytics-service/src/test/java/dev/codearena/analytics/StreaksTest.java`

```java
package dev.codearena.analytics;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class StreaksTest {
    private final LocalDate today = LocalDate.parse("2026-10-11");

    @Test
    void currentStreakIncludesTodayAndLongestIsKept() {
        var days = Set.of("2026-10-01", "2026-10-02", "2026-10-03", "2026-10-04", "2026-10-10", "2026-10-11");
        assertThat(StatsController.streaks(days, today)).containsExactly(2, 4);
    }

    @Test
    void streakSurvivesUntilEndOfNextDay() {
        assertThat(StatsController.streaks(Set.of("2026-10-09", "2026-10-10"), today)).containsExactly(2, 2);
    }

    @Test
    void brokenStreakIsZero() {
        assertThat(StatsController.streaks(Set.of("2026-10-05"), today)).containsExactly(0, 1);
    }

    @Test
    void noActivity() {
        assertThat(StatsController.streaks(Set.of(), today)).containsExactly(0, 0);
    }
}
```

## Check

```bash
./gradlew :services:analytics-service:test :services:analytics-service:bootJar
java -jar services/analytics-service/build/libs/analytics-service.jar
curl -s localhost:9080/api/v1/me/stats -H "authorization: Bearer $TOKEN" | python3 -m json.tool
```

After an accepted submission, `solvedTotal` is 1 and today appears in `heatmap`.
