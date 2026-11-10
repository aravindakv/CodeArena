package dev.codearena.judge;

/**
 * Outcome of judging. failedInput/expected/actual are only filled for visible information:
 * for hidden tests we show the input and expected output of the first failing case, like most judges do.
 */
record Verdict(String status, int passed, int total, long runtimeMs, long memoryKb, String message,
               String failedInput, String expected, String actual, String stdout, String customOutput) {

    static Verdict of(String status, int passed, int total, String message) {
        return new Verdict(status, passed, total, 0, 0, message, null, null, null, null, null);
    }

    Verdict withDebug(String stdout, String customOutput) {
        return new Verdict(status, passed, total, runtimeMs, memoryKb, message, failedInput, expected, actual,
                stdout, customOutput);
    }

    boolean accepted() {
        return "ACCEPTED".equals(status);
    }
}