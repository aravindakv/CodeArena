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