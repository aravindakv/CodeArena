package dev.codearena.judge;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class JudgeTest {
    private final List<String> inputs = List.of("{\"a\":1}", "{\"a\":2}", "{\"a\":3}");
    private final List<String> expected = List.of("1", "4", "9");

    private RunResult run(int exit, long ms, long kb, List<String> results, String stderr) {
        return new RunResult(null, true, false, exit, ms, kb, results, "", 0, stderr, 0);
    }

    private Verdict decide(RunResult r) {
        return Judge.decide(r, inputs, expected, "JSON", 2000, 256, 65536);
    }

    @Test
    void acceptsWhenAllMatch() {
        var v = decide(run(0, 120, 9000, List.of("1", "4", "9"), ""));
        assertThat(v.status()).isEqualTo("ACCEPTED");
        assertThat(v.passed()).isEqualTo(3);
    }

    @Test
    void firstMismatchIsWrongAnswerWithItsInput() {
        var v = decide(run(0, 120, 9000, List.of("1", "5", "9"), ""));
        assertThat(v.status()).isEqualTo("WRONG_ANSWER");
        assertThat(v.passed()).isEqualTo(1);
        assertThat(v.failedInput()).isEqualTo("{\"a\":2}");
        assertThat(v.expected()).isEqualTo("4");
        assertThat(v.actual()).isEqualTo("5");
    }

    @Test
    void killedNearTheLimitIsTimeLimit() {
        assertThat(decide(run(137, 2000, 9000, List.of("1"), "")).status()).isEqualTo("TIME_LIMIT_EXCEEDED");
    }

    @Test
    void killedEarlyIsMemoryLimit() {
        assertThat(decide(run(137, 300, 250_000, List.of(), "")).status()).isEqualTo("MEMORY_LIMIT_EXCEEDED");
    }

    @Test
    void crashIsRuntimeErrorWithStderr() {
        var v = decide(run(1, 50, 9000, List.of("1"), "ZeroDivisionError: division by zero"));
        assertThat(v.status()).isEqualTo("RUNTIME_ERROR");
        assertThat(v.message()).contains("ZeroDivisionError");
        assertThat(v.passed()).isEqualTo(1);
    }

    @Test
    void javaOutOfMemoryIsMemoryLimit() {
        var v = decide(run(1, 50, 9000, List.of(), "Exception in thread \"main\" java.lang.OutOfMemoryError: Java heap space"));
        assertThat(v.status()).isEqualTo("MEMORY_LIMIT_EXCEEDED");
    }

    @Test
    void peakMemoryAboveLimitFailsEvenIfAnswersAreRight() {
        assertThat(decide(run(0, 100, 300 * 1024, List.of("1", "4", "9"), "")).status()).isEqualTo("MEMORY_LIMIT_EXCEEDED");
    }

    @Test
    void compileErrorWins() {
        var r = new RunResult("Main.java:3: error: ';' expected", false, false, -1, 0, 0, List.of(), "", 0, "", 0);
        assertThat(decide(r).status()).isEqualTo("COMPILATION_ERROR");
    }

    @Test
    void wallClockKillIsTimeLimit() {
        var r = new RunResult(null, false, true, -1, 0, 0, List.of(), "", 0, "", -1);
        assertThat(decide(r).status()).isEqualTo("TIME_LIMIT_EXCEEDED");
    }
}