package dev.codearena.common.events;

import java.util.List;

/**
 * Sent by submission-service to SQS (contest-submissions or practice-submissions); consumed by judge-worker.
 * Times are epoch milliseconds so every consumer (Java, Python) parses them the same way.
 *
 * @param mode        RUN (visible examples only, not recorded in stats) or SUBMIT (full hidden test set)
 * @param customInput optional JSON input line for RUN mode, e.g. {"nums":[1,2],"target":3}; its output is shown
 *                    to the user without judging
 */
public record SubmissionCreated(
        String submissionId,
        String userId,
        String slug,
        String lang,
        String mode,
        String contestId,
        long contestStartMs,
        int testsVersion,
        Limits limits,
        String compare,
        String difficulty,
        List<String> tags,
        String customInput,
        long submittedAtMs) {
}