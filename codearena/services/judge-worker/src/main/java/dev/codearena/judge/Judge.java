package dev.codearena.judge;

import java.util.List;

/** Turns a sandbox run into a verdict. Pure logic, unit-tested without Docker. */
final class Judge {
    private Judge() {}

    /**
     * @param inputs   the judged inputs (custom input, if any, is NOT in this list)
     * @param expected expected outputs, same order
     */
    static Verdict decide(RunResult r, List<String> inputs, List<String> expected, String compare,
                          int timeLimitMs, int memoryLimitMb, int maxOutputBytes) {
        int total = expected.size();
        if (r.compileError() != null)
            return Verdict.of("COMPILATION_ERROR", 0, total, r.compileError());
        if (!r.finished()) {
            if (r.wallTimeout()) return Verdict.of("TIME_LIMIT_EXCEEDED", 0, total, "time limit exceeded");
            if (r.containerExit() == 137) return Verdict.of("MEMORY_LIMIT_EXCEEDED", 0, total, "memory limit exceeded");
            throw new IllegalStateException("sandbox ended without a result (exit " + r.containerExit() + ")");
        }

        List<String> got = r.results();
        int n = Math.min(got.size(), total);
        for (int i = 0; i < n; i++) {
            if (!Comparators.equal(compare, expected.get(i), got.get(i))) {
                return new Verdict("WRONG_ANSWER", i, total, r.elapsedMs(), r.maxRssKb(),
                        "wrong answer on test " + (i + 1), inputs.get(i), expected.get(i), truncate(got.get(i)), null, null);
            }
        }
        if (got.size() < total) {
            String failing = inputs.get(got.size());
            if (r.stdoutBytes() >= maxOutputBytes)
                return failed("OUTPUT_LIMIT_EXCEEDED", got.size(), total, r, "too much output", failing);
            if (r.stderr().contains("OutOfMemoryError") || r.stderr().contains("MemoryError")
                    || r.stderr().contains("std::bad_alloc") || r.stderr().contains("heap out of memory"))
                return failed("MEMORY_LIMIT_EXCEEDED", got.size(), total, r, "memory limit exceeded", failing);
            if (r.exitCode() == 137 || r.exitCode() == 124) {
                boolean outOfTime = r.elapsedMs() >= timeLimitMs * 0.95;
                return failed(outOfTime ? "TIME_LIMIT_EXCEEDED" : "MEMORY_LIMIT_EXCEEDED", got.size(), total, r,
                        outOfTime ? "time limit exceeded" : "memory limit exceeded", failing);
            }
            return failed("RUNTIME_ERROR", got.size(), total, r,
                    r.stderr().isBlank() ? "program exited with code " + r.exitCode() : r.stderr(), failing);
        }
        if (r.maxRssKb() > memoryLimitMb * 1024L)
            return failed("MEMORY_LIMIT_EXCEEDED", total, total, r, "memory limit exceeded", null);
        return new Verdict("ACCEPTED", total, total, r.elapsedMs(), r.maxRssKb(), "accepted", null, null, null, null, null);
    }

    private static Verdict failed(String status, int passed, int total, RunResult r, String message, String input) {
        return new Verdict(status, passed, total, r.elapsedMs(), r.maxRssKb(), message, input, null, null, null, null);
    }

    static String truncate(String s) {
        return s == null || s.length() <= 1000 ? s : s.substring(0, 1000) + "...";
    }
}