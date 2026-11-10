package dev.codearena.problem.api;

import dev.codearena.codegen.CodeGenerator;
import dev.codearena.codegen.Lang;
import dev.codearena.codegen.Signature;
import dev.codearena.common.events.Limits;
import dev.codearena.common.web.ApiException;
import dev.codearena.problem.domain.ProblemRecord;
import dev.codearena.problem.domain.ProblemRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Service-to-service API. Not routed by the gateway. */
@RestController
@RequestMapping("/internal")
class InternalController {
    private final ProblemRepository repo;
    private final CodeGenerator codegen;

    InternalController(ProblemRepository repo, CodeGenerator codegen) {
        this.repo = repo;
        this.codegen = codegen;
    }

    /** Everything submission-service and judge-worker need to judge a problem. */
    record JudgeMeta(String slug, String status, int testsVersion, Limits limits, Signature signature,
                     String difficulty, List<String> tags, List<String> visibleInputs, List<String> visibleOutputs) {}

    record CodegenRequest(Signature signature, String lang) {}

    record CodegenResponse(String starter, String harness, String userFile, String harnessFile) {}

    @GetMapping("/problems/{slug}/judge-meta")
    JudgeMeta judgeMeta(@PathVariable String slug) {
        ProblemRecord r = repo.find(slug).orElseThrow(() -> ApiException.notFound("problem"));
        var d = r.doc();
        return new JudgeMeta(d.slug(), r.status(), r.testsVersion(), d.limitsOrDefault(), d.signature(),
                d.difficulty(), d.tags(),
                d.examples().stream().map(e -> ExampleInputs.toJson(e.input(), d.signature())).toList(),
                d.examples().stream().map(e -> e.output().trim()).toList());
    }

    /** Used by the AI ingestion worker to build harnesses for verification in every language. */
    @PostMapping("/codegen")
    CodegenResponse codegen(@RequestBody CodegenRequest req) {
        Lang lang = Lang.of(req.lang());
        return new CodegenResponse(codegen.starter(lang, req.signature()), codegen.harness(lang, req.signature()),
                lang.userFile, lang.harnessFile);
    }
}