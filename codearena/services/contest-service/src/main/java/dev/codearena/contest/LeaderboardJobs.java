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