# 10 · Contest service: fests and a leaderboard that refreshes every 5 seconds

## Scoring (ICPC style)

Rank by problems solved, more is better; break ties by penalty, less is better. Penalty for each solved problem =
minutes from the start to its accepted submission + 20 for each wrong attempt before it. Compilation errors cost
nothing; attempts after a problem is solved are ignored.

Both numbers are packed into one sortable score, so a Redis sorted set ranks everyone:

```
score = solved × 1,000,000 − total penalty minutes
```

## How it handles 100,000 people watching

| Step | Cost |
| --- | --- |
| Each verdict → `score.lua` applies it atomically (dedupe, attempts, score) | O(log N), once per submission |
| Every 5 s → one pod (Redis lock) builds the top-100 snapshot with per-problem cells, stores it with a 10 s TTL | constant, independent of viewers |
| Each viewer → `GET /leaderboard` returns the stored snapshot string, `Cache-Control: max-age=5` | one Redis GET (or a CDN hit on AWS) |
| "Where am I?" → `ZREVRANK` | O(log N) |

All of a contest's keys share the hash tag `{contestId}`, so on Redis Cluster they live on one slot, which `EVAL`
requires. TTLs clean everything up a week after the contest. Freezing the board for the final stretch keeps the
last snapshot without a TTL; finalizing writes the standings to DynamoDB.

**File:** `services/contest-service/build.gradle.kts`

```kotlin
plugins { id("codearena.service") }

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-security-oauth2-resource-server")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("io.awspring.cloud:spring-cloud-aws-starter-dynamodb")
    implementation("io.awspring.cloud:spring-cloud-aws-starter-sqs")
    implementation("com.github.f4b6a3:ulid-creator:${Versions.ULID}")
    // Testcontainers 2 renamed its modules: junit-jupiter -> testcontainers-junit-jupiter
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers")
}
```

**File:** `services/contest-service/src/main/resources/application.yml`

```yaml
server:
  port: ${PORT:9084}
spring:
  application:
    name: contest-service
  config:
    import: classpath:codearena-common.yml

codearena:
  leaderboard:
    refresh-ms: 5000          # snapshot rebuild interval (requirement: every 5 seconds)
    snapshot-ttl: 10s         # a snapshot outlives one missed refresh, then disappears
    top: 100
    wrong-attempt-penalty-min: 20
```

**File:** `services/contest-service/src/main/resources/lua/score.lua`

```lua
-- Atomically applies one judged contest submission to the leaderboard (ICPC rules).
-- KEYS[1] = lb:{cid}                 sorted set  member=userId  score=solved*1e6 - penaltyMinutes
-- KEYS[2] = lbu:{cid}:<uid>          hash        att:<slug> = wrong attempts, ac:<slug> = minute solved
-- KEYS[3] = lb:done:{cid}:<sid>      idempotency marker (SQS may deliver an event twice)
-- ARGV: 1 userId, 2 slug, 3 accepted (1/0), 4 minutesFromStart, 5 ttlSeconds, 6 penaltyPerWrongAttempt
-- All keys share the {cid} hash tag, so on Redis Cluster they live on one slot (required for EVAL).
if redis.call('SET', KEYS[3], '1', 'NX', 'EX', 86400) == false then
  return 0                                        -- duplicate event
end
if redis.call('HEXISTS', KEYS[2], 'ac:' .. ARGV[2]) == 1 then
  return 0                                        -- already solved: later submissions do not count
end
if ARGV[3] == '0' then
  redis.call('HINCRBY', KEYS[2], 'att:' .. ARGV[2], 1)
  redis.call('ZINCRBY', KEYS[1], 0, ARGV[1])      -- make sure the user appears on the board
else
  local wrong = tonumber(redis.call('HGET', KEYS[2], 'att:' .. ARGV[2]) or '0')
  local penalty = tonumber(ARGV[4]) + tonumber(ARGV[6]) * wrong
  redis.call('HSET', KEYS[2], 'ac:' .. ARGV[2], ARGV[4])
  redis.call('ZINCRBY', KEYS[1], 1000000 - penalty, ARGV[1])
end
redis.call('EXPIRE', KEYS[1], tonumber(ARGV[5]))
redis.call('EXPIRE', KEYS[2], tonumber(ARGV[5]))
return 1
```

**File:** `services/contest-service/src/main/java/dev/codearena/contest/ContestApplication.java`

```java
package dev.codearena.contest;

import dev.codearena.common.web.ApiExceptionHandler;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@Import(ApiExceptionHandler.class)
public class ContestApplication {
    public static void main(String[] args) {
        SpringApplication.run(ContestApplication.class, args);
    }
}
```

**File:** `services/contest-service/src/main/java/dev/codearena/contest/SecurityConfig.java`

```java
package dev.codearena.contest;

import dev.codearena.common.security.Roles;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

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
                        .requestMatchers("/actuator/health/**").permitAll()
                        .requestMatchers("/v1/admin/**").hasRole(Roles.ADMIN)
                        .requestMatchers(HttpMethod.GET, "/v1/contests/*/leaderboard/me").authenticated()
                        .requestMatchers(HttpMethod.GET, "/v1/contests", "/v1/contests/*", "/v1/contests/*/leaderboard").permitAll()
                        .requestMatchers(HttpMethod.POST, "/v1/contests/*/register").hasAnyRole(Roles.USER, Roles.ADMIN)
                        .anyRequest().authenticated())
                .oauth2ResourceServer(o -> o.jwt(j -> j.jwtAuthenticationConverter(Roles.jwtConverter())))
                .build();
    }
}
```

**File:** `services/contest-service/src/main/java/dev/codearena/contest/Contest.java`

```java
package dev.codearena.contest;

import java.time.Instant;
import java.util.List;

public record Contest(String id, String title, String description, Instant startAt, Instant endAt,
                      List<String> problems, boolean frozen, boolean finalized, String createdBy) {

    public String phase(Instant now) {
        if (now.isBefore(startAt)) return "UPCOMING";
        if (now.isBefore(endAt)) return "LIVE";
        return "ENDED";
    }

    /** Keys use a {hash tag} so all of one contest's keys land on the same Redis Cluster slot. */
    public static String tag(String id) {
        return "{" + id + "}";
    }
}
```

**File:** `services/contest-service/src/main/java/dev/codearena/contest/ContestRepository.java`

```java
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
```

**File:** `services/contest-service/src/main/java/dev/codearena/contest/Leaderboard.java`

```java
package dev.codearena.contest;

import tools.jackson.databind.json.JsonMapper;
import dev.codearena.common.events.VerdictReady;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static dev.codearena.contest.Contest.tag;

/**
 * Redis keys of one contest (all share the {cid} hash tag):
 *   lb:{cid}              sorted set: userId -> solved*1e6 - penalty     (updated per verdict by score.lua)
 *   lbu:{cid}:<uid>       hash: att:<slug>, ac:<slug>                    (per-problem detail)
 *   lb:names:{cid}        hash: userId -> display name
 *   lb:snap:{cid}         JSON top-100 snapshot, rebuilt every 5 s, TTL 10 s
 *   lb:final:{cid}        JSON final standings after finalize (no TTL)
 *   lb:lock:{cid}         leader lock so only one pod rebuilds the snapshot
 *   contest:{cid}:meta    hash: startMs, endMs, problems   (read by submission-service)
 *   contest:{cid}:reg     set of registered user ids        (read by submission-service)
 */
@Component
class Leaderboard {
    static final long SOLVED_UNIT = 1_000_000L;

    private final StringRedisTemplate redis;
    private final JsonMapper json;
    private final RedisScript<Long> score = RedisScript.of(new ClassPathResource("lua/score.lua"), Long.class);
    private final Duration snapshotTtl;
    private final int top;
    private final int penaltyMin;

    Leaderboard(StringRedisTemplate redis, JsonMapper json,
                @Value("${codearena.leaderboard.snapshot-ttl}") Duration snapshotTtl,
                @Value("${codearena.leaderboard.top}") int top,
                @Value("${codearena.leaderboard.wrong-attempt-penalty-min}") int penaltyMin) {
        this.redis = redis;
        this.json = json;
        this.snapshotTtl = snapshotTtl;
        this.top = top;
        this.penaltyMin = penaltyMin;
    }

    /** Publishes the contest window and problems for submission-service's fast checks. */
    void publishMeta(Contest c) {
        String t = tag(c.id());
        redis.opsForHash().putAll("contest:" + t + ":meta", Map.of(
                "startMs", String.valueOf(c.startAt().toEpochMilli()),
                "endMs", String.valueOf(c.endAt().toEpochMilli()),
                "problems", String.join(",", c.problems())));
        redis.opsForSet().add("contests:active", c.id());
    }

    void register(String contestId, String userId, String name) {
        String t = tag(contestId);
        redis.opsForSet().add("contest:" + t + ":reg", userId);
        redis.opsForHash().put("lb:names:" + t, userId, name);
    }

    long apply(Contest c, VerdictReady v) {
        String t = tag(c.id());
        long ttl = Math.max(3600, Duration.between(Instant.now(), c.endAt().plus(Duration.ofDays(7))).toSeconds());
        Long r = redis.execute(score,
                List.of("lb:" + t, "lbu:" + t + ":" + v.userId(), "lb:done:" + t + ":" + v.submissionId()),
                v.userId(), v.slug(), v.accepted() ? "1" : "0", String.valueOf(v.minutesFromStart()),
                String.valueOf(ttl), String.valueOf(penaltyMin));
        return r == null ? 0 : r;
    }

    /** Builds the top-N snapshot with per-problem cells (one pipelined round trip for all rows). */
    String buildSnapshot(Contest c) throws Exception {
        String t = tag(c.id());
        Set<ZSetOperations.TypedTuple<String>> topSet = redis.opsForZSet().reverseRangeWithScores("lb:" + t, 0, top - 1);
        List<ZSetOperations.TypedTuple<String>> rows = topSet == null ? List.of() : new ArrayList<>(topSet);
        List<String> ids = rows.stream().map(ZSetOperations.TypedTuple::getValue).toList();
        List<Object> names = ids.isEmpty() ? List.of() : redis.opsForHash().multiGet("lb:names:" + t, new ArrayList<>(ids));
        // One round trip for all rows. Raw byte[] keys: the template then deserializes the replies with its
        // own (String) serializers.
        List<Object> details = ids.isEmpty() ? List.of() : redis.executePipelined((RedisCallback<Object>) conn -> {
            for (String id : ids) conn.hashCommands().hGetAll(("lbu:" + t + ":" + id).getBytes(StandardCharsets.UTF_8));
            return null;
        });
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            long sc = rows.get(i).getScore() == null ? 0 : rows.get(i).getScore().longValue();
            long solved = sc <= 0 ? 0 : (sc + SOLVED_UNIT - 1) / SOLVED_UNIT;
            @SuppressWarnings("unchecked")
            Map<Object, Object> d = (Map<Object, Object>) details.get(i);
            Map<String, Object> cells = new LinkedHashMap<>();
            for (String slug : c.problems()) {
                Object ac = d.get("ac:" + slug), att = d.get("att:" + slug);
                if (ac != null || att != null)
                    cells.put(slug, Map.of("solvedAtMin", ac == null ? -1 : Long.parseLong(ac.toString()),
                            "wrong", att == null ? 0 : Long.parseLong(att.toString())));
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("rank", i + 1);
            row.put("userId", ids.get(i));
            row.put("name", names.get(i) == null ? "user-" + ids.get(i).substring(Math.max(0, ids.get(i).length() - 6)) : names.get(i));
            row.put("solved", solved);
            row.put("penalty", solved * SOLVED_UNIT - sc);
            row.put("problems", cells);
            out.add(row);
        }
        Long participants = redis.opsForZSet().zCard("lb:" + t);
        Map<String, Object> snap = new LinkedHashMap<>();
        snap.put("contestId", c.id());
        snap.put("generatedAt", Instant.now().toString());
        snap.put("frozen", c.frozen());
        snap.put("problems", c.problems());
        snap.put("participants", participants == null ? 0 : participants);
        snap.put("rows", out);
        return json.writeValueAsString(snap);
    }

    boolean tryLock(String contestId, String owner, Duration ttl) {
        return Boolean.TRUE.equals(redis.opsForValue().setIfAbsent("lb:lock:" + tag(contestId), owner, ttl));
    }

    void saveSnapshot(String contestId, String snapshot, boolean keepForever) {
        String key = "lb:snap:" + tag(contestId);
        if (keepForever) redis.opsForValue().set(key, snapshot);
        else redis.opsForValue().set(key, snapshot, snapshotTtl);
    }

    String snapshot(String contestId) {
        String s = redis.opsForValue().get("lb:snap:" + tag(contestId));
        return s != null ? s : redis.opsForValue().get("lb:final:" + tag(contestId));
    }

    void saveFinal(String contestId, String json) {
        redis.opsForValue().set("lb:final:" + tag(contestId), json);
        redis.opsForSet().remove("contests:active", contestId);
    }

    Set<String> activeContests() {
        Set<String> s = redis.opsForSet().members("contests:active");
        return s == null ? Set.of() : s;
    }

    Map<String, Object> rankOf(String contestId, String userId) {
        String key = "lb:" + tag(contestId);
        Long r = redis.opsForZSet().reverseRank(key, userId);
        Double sc = redis.opsForZSet().score(key, userId);
        if (r == null || sc == null) return Map.of("rank", 0, "solved", 0, "penalty", 0);
        long score = sc.longValue();
        long solved = score <= 0 ? 0 : (score + SOLVED_UNIT - 1) / SOLVED_UNIT;
        return Map.of("rank", r + 1, "solved", solved, "penalty", solved * SOLVED_UNIT - score);
    }

    /** Full ranking (for finalize). */
    List<ContestRepository.Standing> fullRanking(String contestId) {
        String t = tag(contestId);
        var all = redis.opsForZSet().reverseRangeWithScores("lb:" + t, 0, -1);
        List<ContestRepository.Standing> out = new ArrayList<>();
        if (all == null) return out;
        int rank = 0;
        for (var e : all) {
            long sc = e.getScore() == null ? 0 : e.getScore().longValue();
            long solved = sc <= 0 ? 0 : (sc + SOLVED_UNIT - 1) / SOLVED_UNIT;
            Object name = redis.opsForHash().get("lb:names:" + t, e.getValue());
            out.add(new ContestRepository.Standing(++rank, e.getValue(), name == null ? null : name.toString(),
                    solved, solved * SOLVED_UNIT - sc));
        }
        return out;
    }
}
```

**File:** `services/contest-service/src/main/java/dev/codearena/contest/ScoreListener.java`

```java
package dev.codearena.contest;

import dev.codearena.common.events.Queues;
import dev.codearena.common.events.VerdictReady;
import io.awspring.cloud.sqs.annotation.SqsListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Applies each judged contest submission to the Redis leaderboard. */
@Component
class ScoreListener {
    private static final Logger log = LoggerFactory.getLogger(ScoreListener.class);
    private final ContestRepository contests;
    private final Leaderboard leaderboard;

    ScoreListener(ContestRepository contests, Leaderboard leaderboard) {
        this.contests = contests;
        this.leaderboard = leaderboard;
    }

    @SqsListener(value = Queues.CONTEST_VERDICTS, maxConcurrentMessages = "16")
    void onVerdict(VerdictReady v) {
        if (v.contestId() == null || v.contestId().isBlank()) return;           // practice submission
        var contest = contests.find(v.contestId()).orElse(null);
        if (contest == null || contest.finalized()) return;
        if (v.submittedAtMs() >= contest.endAt().toEpochMilli()) return;         // submitted after the end
        if ("COMPILATION_ERROR".equals(v.verdict()) || "SYSTEM_ERROR".equals(v.verdict())) return;  // no penalty
        long applied = leaderboard.apply(contest, v);
        log.debug("contest {} user {} {} {} -> applied={}", v.contestId(), v.userId(), v.slug(), v.verdict(), applied);
    }
}
```

**File:** `services/contest-service/src/main/java/dev/codearena/contest/LeaderboardJobs.java`

```java
package dev.codearena.contest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Every 5 s: one pod (whoever wins the Redis lock) rebuilds the top-100 snapshot of every active contest.
 * Every 30 s: contests that ended more than 2 minutes ago (late verdicts have arrived) are finalized:
 * standings go to DynamoDB and a permanent final snapshot is kept in Redis.
 */
@Component
class LeaderboardJobs {
    private static final Logger log = LoggerFactory.getLogger(LeaderboardJobs.class);
    private final String podId = UUID.randomUUID().toString();
    private final Leaderboard leaderboard;
    private final ContestRepository contests;

    LeaderboardJobs(Leaderboard leaderboard, ContestRepository contests) {
        this.leaderboard = leaderboard;
        this.contests = contests;
    }

    @Scheduled(fixedRateString = "${codearena.leaderboard.refresh-ms}")
    void refreshSnapshots() {
        for (String id : leaderboard.activeContests()) {
            try {
                var c = contests.find(id).orElse(null);
                if (c == null || c.frozen() || Instant.now().isBefore(c.startAt())) continue;
                if (!leaderboard.tryLock(id, podId, Duration.ofSeconds(4))) continue;
                leaderboard.saveSnapshot(id, leaderboard.buildSnapshot(c), false);
            } catch (Exception e) {
                log.warn("snapshot for {} failed: {}", id, e.getMessage());
            }
        }
    }

    @Scheduled(fixedDelay = 30_000)
    void finalizeEnded() {
        for (String id : leaderboard.activeContests()) {
            var c = contests.find(id).orElse(null);
            if (c == null || c.finalized()) continue;
            if (Instant.now().isAfter(c.endAt().plus(Duration.ofMinutes(2)))) finalizeContest(c);
        }
    }

    void finalizeContest(Contest c) {
        try {
            var standings = leaderboard.fullRanking(c.id());
            contests.writeStandings(c.id(), standings);
            leaderboard.saveFinal(c.id(), leaderboard.buildSnapshot(new Contest(c.id(), c.title(), c.description(),
                    c.startAt(), c.endAt(), c.problems(), false, true, c.createdBy())));
            contests.setFlag(c.id(), "finalized", true);
            log.info("contest {} finalized with {} participants", c.id(), standings.size());
        } catch (Exception e) {
            log.error("finalize {} failed, will retry", c.id(), e);
        }
    }
}
```

**File:** `services/contest-service/src/main/java/dev/codearena/contest/ContestController.java`

```java
package dev.codearena.contest;

import com.github.f4b6a3.ulid.UlidCreator;
import dev.codearena.common.web.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/v1")
class ContestController {
    private final ContestRepository contests;
    private final Leaderboard leaderboard;
    private final LeaderboardJobs jobs;

    ContestController(ContestRepository contests, Leaderboard leaderboard, LeaderboardJobs jobs) {
        this.contests = contests;
        this.leaderboard = leaderboard;
        this.jobs = jobs;
    }

    record ContestView(String id, String title, String description, Instant startAt, Instant endAt, String phase,
                       List<String> problems, boolean frozen, boolean finalized) {}

    record CreateContest(@NotBlank String title, String description, @NotNull Instant startAt,
                         @NotNull Instant endAt, @NotEmpty List<String> problems) {}

    // ---------------- public ----------------

    @GetMapping("/contests")
    List<ContestView> list() {
        Instant now = Instant.now();
        return contests.all().stream().map(c -> view(c, now, false)).toList();
    }

    @GetMapping("/contests/{id}")
    ContestView get(@PathVariable String id, @AuthenticationPrincipal Jwt jwt) {
        boolean admin = jwt != null && String.valueOf(jwt.getClaimAsStringList("roles")).contains("ADMIN");
        return view(find(id), Instant.now(), admin);
    }

    @PostMapping("/contests/{id}/register")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void register(@PathVariable String id, @AuthenticationPrincipal Jwt jwt) {
        Contest c = find(id);
        if (!Instant.now().isBefore(c.endAt())) throw ApiException.badRequest("CONTEST_ENDED", "the contest has ended");
        String name = jwt.getClaimAsString("name") == null ? jwt.getSubject() : jwt.getClaimAsString("name");
        contests.register(id, jwt.getSubject(), name);
        leaderboard.register(id, jwt.getSubject(), name);
    }

    /** The cached snapshot, returned as-is: constant cost however many people watch. */
    @GetMapping(value = "/contests/{id}/leaderboard", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<String> leaderboard(@PathVariable String id) {
        String snap = leaderboard.snapshot(id);
        if (snap == null) snap = "{\"contestId\":\"" + id + "\",\"rows\":[],\"participants\":0}";
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(Duration.ofSeconds(5)).cachePublic()).body(snap);
    }

    @GetMapping("/contests/{id}/leaderboard/me")
    Map<String, Object> me(@PathVariable String id, @AuthenticationPrincipal Jwt jwt) {
        return leaderboard.rankOf(id, jwt.getSubject());
    }

    // ---------------- admin ----------------

    @PostMapping("/admin/contests")
    @PreAuthorize("hasRole('ADMIN')")
    @ResponseStatus(HttpStatus.CREATED)
    ContestView create(@Valid @RequestBody CreateContest req, @AuthenticationPrincipal Jwt admin) {
        if (!req.endAt().isAfter(req.startAt())) throw ApiException.badRequest("BAD_WINDOW", "endAt must be after startAt");
        Contest c = new Contest(UlidCreator.getMonotonicUlid().toString(), req.title(), req.description(),
                req.startAt(), req.endAt(), req.problems(), false, false, admin.getSubject());
        contests.create(c);
        leaderboard.publishMeta(c);
        return view(c, Instant.now(), true);
    }

    @PostMapping("/admin/contests/{id}/freeze")
    @PreAuthorize("hasRole('ADMIN')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void freeze(@PathVariable String id) throws Exception {
        Contest c = find(id);
        leaderboard.saveSnapshot(id, leaderboard.buildSnapshot(c), true);   // keep the frozen board, no TTL
        contests.setFlag(id, "frozen", true);
    }

    @PostMapping("/admin/contests/{id}/unfreeze")
    @PreAuthorize("hasRole('ADMIN')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void unfreeze(@PathVariable String id) {
        find(id);
        contests.setFlag(id, "frozen", false);
    }

    @PostMapping("/admin/contests/{id}/finalize")
    @PreAuthorize("hasRole('ADMIN')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void finalizeNow(@PathVariable String id) {
        jobs.finalizeContest(find(id));
    }

    private Contest find(String id) {
        return contests.find(id).orElseThrow(() -> ApiException.notFound("contest"));
    }

    private static ContestView view(Contest c, Instant now, boolean admin) {
        String phase = c.phase(now);
        List<String> problems = admin || !"UPCOMING".equals(phase) ? c.problems() : List.of();   // hidden until start
        return new ContestView(c.id(), c.title(), c.description(), c.startAt(), c.endAt(), phase, problems,
                c.frozen(), c.finalized());
    }
}
```

This test runs the real Lua script against a real Redis in a container (Testcontainers), including a duplicate
event and an attempt after solving:

**File:** `services/contest-service/src/test/java/dev/codearena/contest/LeaderboardTest.java`

```java
package dev.codearena.contest;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import dev.codearena.common.events.VerdictReady;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Runs the real Lua script against a real Redis (Testcontainers). Skipped when Docker is not available. */
@Testcontainers(disabledWithoutDocker = true)
class LeaderboardTest {
    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);

    static LettuceConnectionFactory factory;
    static Leaderboard board;
    static final JsonMapper JSON = JsonMapper.builder().build();
    static final Contest C = new Contest("c1", "Test fest", "", Instant.now().minusSeconds(600),
            Instant.now().plusSeconds(3600), List.of("a", "b"), false, false, "admin");

    @BeforeAll
    static void setUp() {
        factory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        factory.afterPropertiesSet();
        factory.start();
        StringRedisTemplate t = new StringRedisTemplate(factory);
        t.afterPropertiesSet();
        board = new Leaderboard(t, JSON, Duration.ofSeconds(10), 100, 20);
        board.register("c1", "alice", "alice");
        board.register("c1", "bob", "bob");
    }

    @AfterAll
    static void tearDown() {
        factory.destroy();
    }

    private static VerdictReady v(String sid, String user, String slug, String verdict, long minute) {
        return new VerdictReady(sid, user, slug, "python", "SUBMIT", "c1", verdict, 10, 1000, 0, 0, "EASY",
                List.of(), minute, System.currentTimeMillis());
    }

    @Test
    void ranksBySolvedThenPenaltyAndIgnoresDuplicates() throws Exception {
        board.apply(C, v("s1", "alice", "a", "WRONG_ANSWER", 3));
        board.apply(C, v("s2", "alice", "a", "ACCEPTED", 10));     // 10 + 20 penalty = 30
        board.apply(C, v("s2", "alice", "a", "ACCEPTED", 10));     // duplicate delivery: ignored
        board.apply(C, v("s3", "bob", "a", "ACCEPTED", 12));       // 12
        board.apply(C, v("s4", "bob", "b", "ACCEPTED", 40));       // 12 + 40 = 52, two solved
        board.apply(C, v("s5", "bob", "b", "WRONG_ANSWER", 41));   // after solving: ignored

        JsonNode snap = JSON.readTree(board.buildSnapshot(C));
        JsonNode rows = snap.get("rows");
        assertThat(rows.get(0).get("userId").asString()).isEqualTo("bob");
        assertThat(rows.get(0).get("solved").asLong()).isEqualTo(2);
        assertThat(rows.get(0).get("penalty").asLong()).isEqualTo(52);
        assertThat(rows.get(1).get("userId").asString()).isEqualTo("alice");
        assertThat(rows.get(1).get("penalty").asLong()).isEqualTo(30);
        assertThat(rows.get(1).get("problems").get("a").get("wrong").asLong()).isEqualTo(1);
        assertThat(board.rankOf("c1", "alice").get("rank")).isEqualTo(2L);
    }
}
```

## Check

```bash
./gradlew :services:contest-service:test
./gradlew :services:contest-service:bootJar && java -jar services/contest-service/build/libs/contest-service.jar
START=$(date -u -d '-1 min' +%FT%TZ); END=$(date -u -d '+60 min' +%FT%TZ)
curl -s -X POST localhost:9080/api/v1/admin/contests -H "authorization: Bearer $TOKEN" -H 'content-type: application/json' \
  -d "{\"title\":\"Trial fest\",\"startAt\":\"$START\",\"endAt\":\"$END\",\"problems\":[\"pair-with-target-sum\",\"balanced-brackets\"]}"
```

Register, submit with `"contestId"`, wait five seconds, and `GET /api/v1/contests/<id>/leaderboard` shows you.
