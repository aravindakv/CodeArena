package dev.codearena.judge;

import dev.codearena.codegen.Lang;
import dev.codearena.common.events.Limits;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Runs untrusted code in a throw-away container. Every limit that protects the host is set here:
 * no network, read-only root, capped tmpfs, memory and swap, CPU, process count, open files, file size,
 * no capabilities, no privilege escalation, unprivileged user, gVisor kernel (runsc), wall-clock kill.
 */
@Component
class Sandbox {
    private static final Logger log = LoggerFactory.getLogger(Sandbox.class);
    private final JudgeProps props;

    Sandbox(JudgeProps props) {
        this.props = props;
        if (!"runsc".equals(props.runtime()))
            log.warn("JUDGE_RUNTIME={} - user code runs WITHOUT gVisor. Acceptable on a dev machine only.", props.runtime());
    }

    RunResult run(Lang lang, String userCode, String harness, List<String> inputs, Limits limits) throws IOException, InterruptedException {
        LangRuntime rt = LangRuntime.of(lang);
        Path root = Files.createDirectories(Path.of(props.workRoot()));
        Path job = Files.createTempDirectory(root, "job-");
        Path work = Files.createDirectories(job.resolve("work"));
        Path out = Files.createDirectories(job.resolve("out"));
        String name = "ca-" + UUID.randomUUID();
        try {
            Files.writeString(work.resolve(lang.userFile), userCode, StandardCharsets.UTF_8);
            Files.writeString(work.resolve(lang.harnessFile), harness, StandardCharsets.UTF_8);
            Files.write(work.resolve("inputs.jsonl"), inputs, StandardCharsets.UTF_8);
            Files.setPosixFilePermissions(job, PosixFilePermissions.fromString("rwxr-xr-x"));
            Files.setPosixFilePermissions(work, PosixFilePermissions.fromString("rwxr-xr-x"));
            try (Stream<Path> files = Files.list(work)) {
                for (Path f : files.toList()) Files.setPosixFilePermissions(f, PosixFilePermissions.fromString("rw-r--r--"));
            }
            Files.setPosixFilePermissions(out, PosixFilePermissions.fromString("rwxrwxrwx"));   // container user 65534 writes here

            int timeMs = limits.timeMs() + rt.startupMs();
            int memoryMb = limits.memoryMb() + rt.extraMemoryMb();
            List<String> cmd = new ArrayList<>(List.of("docker", "run", "--rm", "--name", name,
                    "--runtime=" + props.runtime(),
                    "--network=none",
                    "--read-only",
                    "--tmpfs", "/tmp:" + rt.tmpfs(),
                    "--memory=" + memoryMb + "m", "--memory-swap=" + memoryMb + "m",
                    "--cpus=1", "--pids-limit=128",
                    "--cap-drop=ALL", "--security-opt=no-new-privileges",
                    "--user", "65534:65534",
                    "--ulimit", "nofile=64:64", "--ulimit", "fsize=10485760:10485760",
                    "-e", "TIME_LIMIT_MS=" + timeMs,
                    "-e", "MAX_OUT_BYTES=" + props.maxOutputBytes(),
                    "-e", "MEMORY_MB=" + limits.memoryMb(),
                    "-v", work.toAbsolutePath() + ":/work:ro",
                    "-v", out.toAbsolutePath() + ":/out:rw",
                    lang.image()));

            Process p = new ProcessBuilder(cmd).redirectErrorStream(true)
                    .redirectOutput(job.resolve("docker.log").toFile()).start();
            long wallMs = timeMs + rt.compileBudgetMs() + 10_000L;
            boolean wallTimeout = !p.waitFor(wallMs, TimeUnit.MILLISECONDS);
            if (wallTimeout) {
                log.warn("wall-clock timeout after {} ms, killing {}", wallMs, name);
                new ProcessBuilder("docker", "kill", name).start().waitFor(10, TimeUnit.SECONDS);
                p.waitFor(10, TimeUnit.SECONDS);
            }
            int containerExit = p.isAlive() ? -1 : p.exitValue();
            if (containerExit == 125 || containerExit == 126 || containerExit == 127) {
                // docker itself failed (image missing, runtime not installed...). Not the user's fault.
                throw new IllegalStateException("docker run failed (" + containerExit + "): " + head(job.resolve("docker.log"), 2000));
            }
            return read(out, wallTimeout, containerExit);
        } finally {
            deleteQuietly(job);
        }
    }

    private RunResult read(Path out, boolean wallTimeout, int containerExit) throws IOException {
        String meta = Files.exists(out.resolve("meta")) ? Files.readString(out.resolve("meta")).trim() : "";
        String compileErr = meta.startsWith("COMPILE") ? head(out.resolve("compile.err"), 4000) : null;
        boolean finished = meta.startsWith("RUN");
        int exit = -1;
        long elapsed = 0;
        if (finished) {
            String[] parts = meta.split("\\s+");
            exit = Integer.parseInt(parts[1]);
            elapsed = Long.parseLong(parts[2]);
        }
        long rss = 0;
        try {
            String r = Files.exists(out.resolve("maxrss_kb")) ? Files.readString(out.resolve("maxrss_kb")).trim() : "";
            // GNU time may print "Command terminated by signal 9" before the number: take the last line.
            String[] lines = r.split("\n");
            rss = lines.length == 0 || lines[lines.length - 1].isBlank() ? 0 : Long.parseLong(lines[lines.length - 1].trim());
        } catch (NumberFormatException ignored) {
            // leave 0
        }
        List<String> results = Files.exists(out.resolve("results.jsonl"))
                ? Files.readAllLines(out.resolve("results.jsonl")).stream().filter(l -> !l.isBlank()).toList()
                : List.of();
        long stdoutBytes = Files.exists(out.resolve("stdout")) ? Files.size(out.resolve("stdout")) : 0;
        return new RunResult(compileErr, finished, wallTimeout, exit, elapsed, rss, results,
                head(out.resolve("stdout"), 4000), stdoutBytes, head(out.resolve("stderr"), 4000), containerExit);
    }

    static String head(Path p, int maxChars) throws IOException {
        if (!Files.exists(p)) return "";
        byte[] b = Files.readAllBytes(p);
        String s = new String(b, 0, Math.min(b.length, maxChars * 2), StandardCharsets.UTF_8);
        return s.length() > maxChars ? s.substring(0, maxChars) + "\n... (truncated)" : s;
    }

    private static void deleteQuietly(Path dir) {
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(f -> f.toFile().delete());
        } catch (IOException e) {
            log.warn("could not delete {}: {}", dir, e.getMessage());
        }
    }
}