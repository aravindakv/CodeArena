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