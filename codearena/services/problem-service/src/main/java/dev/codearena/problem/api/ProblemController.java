package dev.codearena.problem.api;

import dev.codearena.codegen.CodeGenerator;
import dev.codearena.codegen.Lang;
import dev.codearena.common.events.Limits;
import dev.codearena.common.web.ApiException;
import dev.codearena.problem.domain.ProblemCatalog;
import dev.codearena.problem.domain.ProblemDoc;
import dev.codearena.problem.domain.ProblemRecord;
import dev.codearena.problem.domain.ProblemRepository;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;

/** Public, read-only API used by web and mobile clients. Paths are /v1/... (the gateway adds /api). */
@RestController
@RequestMapping("/v1")
@Validated
class ProblemController {
    private final ProblemCatalog catalog;
    private final ProblemRepository repo;
    private final CodeGenerator codegen;

    ProblemController(ProblemCatalog catalog, ProblemRepository repo, CodeGenerator codegen) {
        this.catalog = catalog;
        this.repo = repo;
        this.codegen = codegen;
    }

    record ProblemDetail(int number, String slug, String title, String difficulty, List<String> tags,
                         String statement, List<String> constraints, List<ProblemDoc.Example> examples,
                         List<String> hints, Limits limits, double acceptance, List<String> languages) {}

    record LanguageInfo(String id, String label, String monacoId) {}

    @GetMapping("/problems")
    ResponseEntity<ProblemCatalog.Page> list(@RequestParam(defaultValue = "1") @Min(1) int page,
                                             @RequestParam(defaultValue = "100") @Min(1) @Max(100) int size,
                                             @RequestParam(required = false) String difficulty,
                                             @RequestParam(required = false) String tag,
                                             @RequestParam(required = false) String q) {
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(Duration.ofSeconds(30)).cachePublic())
                .body(catalog.page(page, size, difficulty, tag, q));
    }

    @GetMapping("/problems/tags")
    List<String> tags() {
        return catalog.tags();
    }

    @GetMapping("/problems/{slug}")
    ResponseEntity<ProblemDetail> get(@PathVariable String slug) {
        ProblemRecord r = published(slug);
        ProblemDoc d = r.doc();
        var body = new ProblemDetail(r.number(), d.slug(), d.title(), d.difficulty(), d.tags(), d.statement(),
                d.constraints(), d.examples(), d.hints() == null ? List.of() : d.hints(), d.limitsOrDefault(),
                r.acceptance(), Arrays.stream(Lang.values()).map(l -> l.id).toList());
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(Duration.ofSeconds(60)).cachePublic())
                .eTag("\"" + r.updatedAt() + "\"").body(body);
    }

    @GetMapping(value = "/problems/{slug}/starter/{lang}", produces = MediaType.TEXT_PLAIN_VALUE)
    String starter(@PathVariable String slug, @PathVariable String lang) {
        return codegen.starter(Lang.of(lang), published(slug).doc().signature());
    }

    @GetMapping("/languages")
    List<LanguageInfo> languages() {
        return Arrays.stream(Lang.values()).map(l -> new LanguageInfo(l.id, l.label, l.monacoId)).toList();
    }

    private ProblemRecord published(String slug) {
        return repo.find(slug).filter(ProblemRecord::published).orElseThrow(() -> ApiException.notFound("problem"));
    }
}