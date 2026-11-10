package dev.codearena.judge;

import java.util.List;

/**
 * What came back from one sandbox run.
 *
 * @param compileError compiler output if compilation failed, else null
 * @param finished     false when the container was killed from outside (wall clock) or died before writing /out/meta
 * @param exitCode     exit status of the program (137 = killed by SIGKILL: time or memory)
 * @param elapsedMs    run time of the program over all inputs
 * @param maxRssKb     peak memory of the program
 * @param results      one JSON line per input the program completed
 * @param stdout       the program's own prints (capped), for debugging
 * @param stderr       first part of stderr (stack traces)
 * @param containerExit exit status of `docker run` itself
 */
record RunResult(String compileError, boolean finished, boolean wallTimeout, int exitCode, long elapsedMs,
                 long maxRssKb, List<String> results, String stdout, long stdoutBytes, String stderr,
                 int containerExit) {
}