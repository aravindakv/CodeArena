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