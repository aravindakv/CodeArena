package dev.codearena.submission;

import tools.jackson.databind.json.JsonMapper;
import dev.codearena.common.events.Limits;
import dev.codearena.common.web.ApiException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;

/**
 * Fetches judge metadata from problem-service (/internal/...) and caches it in Redis for 5 minutes,
 * so a contest burst of thousands of submissions per second does not hammer problem-service.
 * A circuit breaker stops calling problem-service while it is failing and answers 503 at once.
 */
@Component
class ProblemClient {
    private final RestClient http;
    private final StringRedisTemplate redis;
    private final JsonMapper json;
    private final CircuitBreaker breaker = CircuitBreaker.of("problems", CircuitBreakerConfig.custom()
            .slidingWindowSize(20)
            .minimumNumberOfCalls(10)
            .failureRateThreshold(50)
            .waitDurationInOpenState(Duration.ofSeconds(10))
            .ignoreExceptions(ApiException.class)          // a 404 is an answer, not a failure
            .build());

    ProblemClient(@Value("${codearena.problem-url}") String baseUrl, StringRedisTemplate redis, JsonMapper json) {
        this.http = RestClient.create(baseUrl);
        this.redis = redis;
        this.json = json;
    }

    record Signature(String function, List<Object> params, String returns, String compare) {}

    record JudgeMeta(String slug, String status, int testsVersion, Limits limits, Signature signature,
                     String difficulty, List<String> tags, List<String> visibleInputs, List<String> visibleOutputs) {}

    JudgeMeta meta(String slug) {
        String key = "judge-meta:" + slug;
        try {
            String cached = redis.opsForValue().get(key);
            if (cached != null) return json.readValue(cached, JudgeMeta.class);
        } catch (Exception ignored) {
            // cache miss or Redis down: fall through to the service
        }
        JudgeMeta meta;
        try {
            meta = breaker.executeSupplier(() -> {
                try {
                    return http.get().uri("/internal/problems/{slug}/judge-meta", slug).retrieve().body(JudgeMeta.class);
                } catch (HttpClientErrorException.NotFound e) {
                    throw ApiException.notFound("problem");
                }
            });
        } catch (CallNotPermittedException | ResourceAccessException e) {
            throw unavailable();
        }
        try {
            redis.opsForValue().set(key, json.writeValueAsString(meta), Duration.ofMinutes(5));
        } catch (Exception ignored) {
            // caching is best effort
        }
        return meta;
    }

    static ApiException unavailable() {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "PROBLEMS_UNAVAILABLE", "try again shortly");
    }
}