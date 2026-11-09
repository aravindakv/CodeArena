package dev.codearena.common.events;

import java.util.List;

/**
 * Published by judge-worker after a submission is judged. Locally it is sent to two SQS queues
 * (verdict-events for analytics, contest-verdicts for the leaderboard); on AWS this becomes one
 * EventBridge event with two rules.
 */
public record VerdictReady(
        String submissionId,
        String userId,
        String slug,
        String lang,
        String mode,
        String contestId,
        String verdict,
        long runtimeMs,
        long memoryKb,
        int passed,
        int total,
        String difficulty,
        List<String> tags,
        long minutesFromStart,
        long submittedAtMs) {

    public boolean accepted() {
        return "ACCEPTED".equals(verdict);
    }
}