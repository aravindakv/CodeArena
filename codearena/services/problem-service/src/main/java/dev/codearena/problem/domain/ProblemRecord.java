package dev.codearena.problem.domain;

/** A Problems table META item: the doc plus lifecycle fields. */
public record ProblemRecord(ProblemDoc doc, String status, int number, int testsVersion, String source,
                           long submits, long accepts, String updatedAt) {

    public boolean published() {
        return "PUBLISHED".equals(status);
    }

    public double acceptance() {
        return submits == 0 ? 0 : Math.round(1000.0 * accepts / submits) / 10.0;
    }
}