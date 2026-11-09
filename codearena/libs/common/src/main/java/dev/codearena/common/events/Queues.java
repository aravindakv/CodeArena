package dev.codearena.common.events;

/** SQS queue names, created by deploy/compose/elasticmq.conf locally. */
public final class Queues {
    private Queues() {}

    public static final String CONTEST_SUBMISSIONS = "contest-submissions";
    public static final String PRACTICE_SUBMISSIONS = "practice-submissions";
    public static final String VERDICT_EVENTS = "verdict-events";
    public static final String CONTEST_VERDICTS = "contest-verdicts";
    public static final String INGESTION_JOBS = "ingestion-jobs";
}