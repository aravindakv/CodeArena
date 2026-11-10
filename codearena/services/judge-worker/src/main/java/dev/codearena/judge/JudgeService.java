package dev.codearena.judge;

import dev.codearena.codegen.CodeGenerator;
import dev.codearena.codegen.Lang;
import dev.codearena.common.events.Limits;
import dev.codearena.common.events.SubmissionCreated;
import dev.codearena.common.events.VerdictReady;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
class JudgeService {
    private static final Logger log = LoggerFactory.getLogger(JudgeService.class);

    private final Sandbox sandbox;
    private final TestCases tests;
    private final ProblemMetaClient problems;
    private final SubmissionStore store;
    private final VerdictPublisher publisher;
    private final JudgeProps props;
    private final CodeGenerator codegen = new CodeGenerator();

    JudgeService(Sandbox sandbox, TestCases tests, ProblemMetaClient problems, SubmissionStore store,
                 VerdictPublisher publisher, JudgeProps props) {
        this.sandbox = sandbox;
        this.tests = tests;
        this.problems = problems;
        this.store = store;
        this.publisher = publisher;
        this.props = props;
    }

    void judge(SubmissionCreated s) throws Exception {
        if (!store.isPending(s.userId(), s.submissionId())) {
            log.info("{} already judged, skipping duplicate delivery", s.submissionId());
            return;
        }
        long t0 = System.currentTimeMillis();
        Lang lang = Lang.of(s.lang());
        var meta = problems.get(s.slug());
        Limits limits = s.limits() != null ? s.limits() : meta.limits();

        List<String> inputs, expected;
        if ("RUN".equals(s.mode())) {
            inputs = new ArrayList<>(meta.visibleInputs());
            expected = meta.visibleOutputs();
        } else {
            var cases = tests.load(s.slug(), s.testsVersion());
            inputs = new ArrayList<>(cases.inputs());
            expected = cases.expected();
        }
        List<String> judged = List.copyOf(inputs);
        boolean custom = s.customInput() != null && !s.customInput().isBlank();
        if (custom) inputs.add(s.customInput().trim());

        String code = store.source(s.submissionId());
        String harness = codegen.harness(lang, meta.signature());
        RunResult run = sandbox.run(lang, code, harness, inputs, limits);

        Verdict v = Judge.decide(run, judged, expected, meta.signature().compare(),
                limits.timeMs() + LangRuntime.of(lang).startupMs(), limits.memoryMb(), props.maxOutputBytes());
        String customOutput = custom && run.results().size() > judged.size() ? run.results().get(judged.size()) : null;
        v = v.withDebug(run.stdout(), customOutput);

        if (store.finish(s.userId(), s.submissionId(), v) && "SUBMIT".equals(s.mode())) {
            long minutes = s.contestStartMs() > 0 ? Math.max(0, (s.submittedAtMs() - s.contestStartMs()) / 60_000) : 0;
            publisher.publish(new VerdictReady(s.submissionId(), s.userId(), s.slug(), s.lang(), s.mode(),
                    s.contestId(), v.status(), v.runtimeMs(), v.memoryKb(), v.passed(), v.total(),
                    s.difficulty(), s.tags(), minutes, s.submittedAtMs()));
        }
        log.info("{} {} {} -> {} ({}/{} passed, {} ms run, {} ms total)", s.submissionId(), s.slug(), s.lang(),
                v.status(), v.passed(), v.total(), v.runtimeMs(), System.currentTimeMillis() - t0);
    }

    /** Called after repeated failures: never leave a user waiting on PENDING forever. */
    void giveUp(SubmissionCreated s, Exception cause) {
        Verdict v = Verdict.of("SYSTEM_ERROR", 0, 0, "the judge could not run this submission; please retry");
        store.finish(s.userId(), s.submissionId(), v);
        log.error("{} gave up after repeated failures", s.submissionId(), cause);
    }
}