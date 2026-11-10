package dev.codearena.submission;

import dev.codearena.common.web.ApiException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Map;

/**
 * Contest checks straight from Redis (contest-service keeps these keys up to date), so the hot submit path
 * needs no extra HTTP call during a fest:
 *   contest:{cid}:meta  hash  startMs, endMs, problems (comma separated slugs)
 *   contest:{cid}:reg   set   registered user ids
 */
@Component
class ContestGate {
    private final StringRedisTemplate redis;

    ContestGate(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** Returns the contest start (epoch ms) if the user may submit this problem now; throws otherwise. */
    long requireOpen(String contestId, String userId, String slug) {
        Map<Object, Object> meta = redis.opsForHash().entries("contest:{" + contestId + "}:meta");
        if (meta.isEmpty()) throw ApiException.notFound("contest");
        long now = System.currentTimeMillis();
        long start = Long.parseLong((String) meta.get("startMs"));
        long end = Long.parseLong((String) meta.get("endMs"));
        if (now < start || now >= end) throw ApiException.forbidden("CONTEST_NOT_LIVE", "the contest is not running");
        if (!Arrays.asList(((String) meta.get("problems")).split(",")).contains(slug))
            throw ApiException.badRequest("NOT_IN_CONTEST", "this problem is not part of the contest");
        if (!Boolean.TRUE.equals(redis.opsForSet().isMember("contest:{" + contestId + "}:reg", userId)))
            throw ApiException.forbidden("NOT_REGISTERED", "register for the contest first");
        return start;
    }
}