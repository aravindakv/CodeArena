package dev.codearena.judge;

import dev.codearena.codegen.CodeGenerator;
import dev.codearena.codegen.Lang;
import dev.codearena.codegen.Signature;
import dev.codearena.common.events.Limits;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Malicious and broken programs against the REAL sandbox. Needs Docker and the runner images:
 *   sandbox-images/build-all.sh python
 *   ./gradlew :services:judge-worker:test -Psandbox
 * Set JUDGE_RUNTIME=runc if gVisor is not installed (then these prove the Docker limits only).
 */
@Tag("sandbox")
class SandboxRedTeamTest {
    private static final Signature SIG = new Signature("double_it",
            List.of(new Signature.Param("x", "int")), "int", "JSON");
    private static final List<String> INPUTS = List.of("{\"x\":1}", "{\"x\":2}");
    private static final List<String> EXPECTED = List.of("2", "4");

    private final Sandbox sandbox = new Sandbox(new JudgeProps(
            System.getenv().getOrDefault("JUDGE_RUNTIME", "runsc"), System.getProperty("java.io.tmpdir") + "/ca-redteam",
            "http://unused", "unused", "unused", 65536, List.of()));
    private final CodeGenerator codegen = new CodeGenerator();

    private Verdict judge(String body) throws Exception {
        String code = "class Solution:\n    def double_it(self, x: int) -> int:\n" + body.indent(8);
        RunResult r = sandbox.run(Lang.PYTHON, code, codegen.harness(Lang.PYTHON, SIG), INPUTS, new Limits(2000, 128));
        return Judge.decide(r, INPUTS, EXPECTED, "JSON", 2200, 128, 65536);
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', textBlock = """
            correct solution        | return x * 2                                                            | ACCEPTED
            wrong answer            | return x + 2                                                            | WRONG_ANSWER
            infinite loop           | while True: pass                                                        | TIME_LIMIT_EXCEEDED
            fork bomb               | import os\\nwhile True: os.fork()                                        | RUNTIME_ERROR/MEMORY_LIMIT_EXCEEDED
            memory bomb             | x = [1] * (300 * 1024 * 1024)\\nreturn 0                               | MEMORY_LIMIT_EXCEEDED
            network call            | import urllib.request\\nurllib.request.urlopen('http://1.1.1.1', timeout=2)\\nreturn x * 2 | RUNTIME_ERROR
            cloud metadata          | import urllib.request\\nurllib.request.urlopen('http://169.254.169.254/', timeout=2)\\nreturn x * 2 | RUNTIME_ERROR
            write outside tmp       | open('/etc/hacked', 'w').write('x')\\nreturn x * 2                      | RUNTIME_ERROR
            fill tmp disk           | f = open('/tmp/big', 'wb')\\nwhile True: f.write(b'0' * 1048576)        | RUNTIME_ERROR
            output flood            | import sys\\nwhile True: sys.stdout.write('x' * 10000)                  | OUTPUT_LIMIT_EXCEEDED
            syntax error            | return x *                                                              | COMPILATION_ERROR
            """)
    void sandboxContainsIt(String name, String body, String expected) throws Exception {
        Verdict v = judge(body.replace("\\n", "\n"));
        // "A/B" = either is a correct containment. A fork bomb hits the pids limit (RUNTIME_ERROR) or,
        // since ~128 Python processes outgrow 128 MB, the memory cgroup first (MEMORY_LIMIT_EXCEEDED).
        assertThat(v.status()).as(name + " -> " + v.message()).isIn((Object[]) expected.split("/"));
    }
}