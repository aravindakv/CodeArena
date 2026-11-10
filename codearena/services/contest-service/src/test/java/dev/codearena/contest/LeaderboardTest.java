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