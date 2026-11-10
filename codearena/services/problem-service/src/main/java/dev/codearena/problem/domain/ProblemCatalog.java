package dev.codearena.problem.domain;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * The published problem list, cached in Redis as one JSON array (a few hundred KB even at 5,000 problems).
 * Paging, difficulty/tag filters and title search run in memory on that list, so the list endpoint never
 * touches DynamoDB on the hot path. Publishing or retiring a problem bumps a version number, which makes
 * every cached copy stale at once.
 */
@Service
public class ProblemCatalog {
    private static final Logger log = LoggerFactory.getLogger(ProblemCatalog.class);
    private static final String VERSION_KEY = "problems:list:version";
    private static final TypeReference<List<ProblemSummary>> LIST = new TypeReference<>() {};

    private final ProblemRepository repo;
    private final StringRedisTemplate redis;
    private final JsonMapper json;
    private final Duration ttl;

    public ProblemCatalog(ProblemRepository repo, StringRedisTemplate redis, JsonMapper json,
                          @Value("${codearena.problem-list-cache-ttl}") Duration ttl) {
        this.repo = repo;
        this.redis = redis;
        this.json = json;
        this.ttl = ttl;
    }

    public record Page(List<ProblemSummary> items, int page, int size, int totalItems, int totalPages) {}

    public Page page(int page, int size, String difficulty, String tag, String q) {
        List<ProblemSummary> all = all().stream()
                .filter(p -> difficulty == null || difficulty.isBlank() || p.difficulty().equalsIgnoreCase(difficulty))
                .filter(p -> tag == null || tag.isBlank() || p.tags().contains(tag))
                .filter(p -> q == null || q.isBlank() || p.title().toLowerCase(Locale.ROOT).contains(q.toLowerCase(Locale.ROOT))
                        || String.valueOf(p.number()).equals(q.trim()))
                .toList();
        int totalPages = Math.max(1, (all.size() + size - 1) / size);
        int from = Math.min((page - 1) * size, all.size());
        return new Page(all.subList(from, Math.min(from + size, all.size())), page, size, all.size(), totalPages);
    }

    public List<String> tags() {
        return all().stream().flatMap(p -> p.tags().stream()).distinct().sorted().toList();
    }

    List<ProblemSummary> all() {
        String key = "problems:list:v" + version();
        try {
            String cached = redis.opsForValue().get(key);
            if (cached != null) return json.readValue(cached, LIST);
        } catch (Exception e) {
            log.warn("problem list cache unavailable ({}); reading DynamoDB", e.getMessage());
        }
        List<ProblemSummary> fresh = repo.allPublished().stream()
                .map(r -> new ProblemSummary(r.number(), r.doc().slug(), r.doc().title(), r.doc().difficulty(),
                        r.doc().tags(), r.acceptance()))
                .toList();
        try {
            redis.opsForValue().set(key, json.writeValueAsString(fresh), ttl);
        } catch (Exception e) {
            log.warn("could not cache problem list: {}", e.getMessage());
        }
        return fresh;
    }

    public void invalidate() {
        try {
            redis.opsForValue().increment(VERSION_KEY);
        } catch (Exception e) {
            log.warn("could not invalidate problem list cache: {}", e.getMessage());
        }
    }

    private String version() {
        try {
            String v = redis.opsForValue().get(VERSION_KEY);
            return v == null ? "0" : v;
        } catch (Exception e) {
            return "0";
        }
    }

    static List<String> split(String csv) {
        return csv == null || csv.isBlank() ? List.of() : Arrays.asList(csv.split(","));
    }
}