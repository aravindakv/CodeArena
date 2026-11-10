package dev.codearena.problem.admin;

import dev.codearena.codegen.CodeGenerator;
import dev.codearena.codegen.Lang;
import dev.codearena.common.web.ApiException;
import dev.codearena.problem.domain.ProblemCatalog;
import dev.codearena.problem.domain.ProblemDoc;
import dev.codearena.problem.domain.ProblemRecord;
import dev.codearena.problem.domain.ProblemRepository;
import dev.codearena.problem.domain.TestStore;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * ADMIN only (checked by the gateway and again here).
 * Lifecycle: DRAFT / IN_REVIEW  --publish-->  PUBLISHED  --retire-->  RETIRED;  IN_REVIEW --reject--> REJECTED
 */
@RestController
@RequestMapping("/v1/admin/problems")
@PreAuthorize("hasRole('ADMIN')")
class AdminProblemController {
    private final ProblemRepository repo;
    private final TestStore tests;
    private final ProblemCatalog catalog;
    private final CodeGenerator codegen;
    private final AuditLog audit;

    AdminProblemController(ProblemRepository repo, TestStore tests, ProblemCatalog catalog, CodeGenerator codegen,
                           AuditLog audit) {
        this.repo = repo;
        this.tests = tests;
        this.catalog = catalog;
        this.codegen = codegen;
        this.audit = audit;
    }

    record AdminRow(String slug, String title, String difficulty, String status, int number, int testsVersion,
                    String source, String updatedAt) {}

    record AdminDetail(ProblemRecord record, Map<String, String> starters) {}

    @GetMapping
    List<AdminRow> list(@RequestParam(defaultValue = "IN_REVIEW") String status) {
        return repo.byStatus(status).stream()
                .sorted(Comparator.comparing((ProblemRecord r) -> String.valueOf(r.updatedAt())).reversed())
                .map(r -> new AdminRow(r.doc().slug(), r.doc().title(), r.doc().difficulty(), r.status(), r.number(),
                        r.testsVersion(), r.source(), r.updatedAt()))
                .toList();
    }

    @GetMapping("/{slug}")
    AdminDetail get(@PathVariable String slug) {
        ProblemRecord r = find(slug);
        Map<String, String> starters = new java.util.LinkedHashMap<>();
        Arrays.stream(Lang.values()).forEach(l -> starters.put(l.id, codegen.starter(l, r.doc().signature())));
        return new AdminDetail(r, starters);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    void create(@Valid @RequestBody ProblemDoc doc, @AuthenticationPrincipal Jwt admin) {
        doc.signature().validate();
        repo.create(doc, "DRAFT", "manual:" + admin.getSubject());
        audit.record(admin.getSubject(), "CREATE_PROBLEM", doc.slug());
    }

    @PutMapping("/{slug}")
    void update(@PathVariable String slug, @Valid @RequestBody ProblemDoc doc, @AuthenticationPrincipal Jwt admin) {
        if (!slug.equals(doc.slug())) throw ApiException.badRequest("SLUG_MISMATCH", "slug cannot change");
        doc.signature().validate();
        find(slug);
        repo.updateDoc(doc);
        catalog.invalidate();
        audit.record(admin.getSubject(), "UPDATE_PROBLEM", slug);
    }

    /** Body: JSON Lines, one {"input": {...}, "output": ...} per line. Creates a new tests version. */
    @PutMapping(value = "/{slug}/tests", consumes = {"application/x-ndjson", "text/plain"})
    Map<String, Integer> uploadTests(@PathVariable String slug, @RequestBody String jsonl, @AuthenticationPrincipal Jwt admin) {
        ProblemRecord r = find(slug);
        int version = r.testsVersion() + 1;
        int count = tests.upload(slug, version, jsonl, r.doc().signature().params().stream().map(p -> p.name()).toList());
        repo.setTestsVersion(slug, version);
        audit.record(admin.getSubject(), "UPLOAD_TESTS", slug + "@v" + version);
        return Map.of("testsVersion", version, "cases", count);
    }

    @PostMapping("/{slug}/publish")
    Map<String, Integer> publish(@PathVariable String slug, @AuthenticationPrincipal Jwt admin) {
        ProblemRecord r = find(slug);
        if (r.published()) throw ApiException.conflict("ALREADY_PUBLISHED", "already published as #" + r.number());
        if (!tests.exists(slug, r.testsVersion()))
            throw ApiException.badRequest("NO_TESTS", "upload hidden test cases before publishing");
        int number = repo.publish(slug);
        catalog.invalidate();
        audit.record(admin.getSubject(), "PUBLISH_PROBLEM", slug + "#" + number);
        return Map.of("number", number);
    }

    @PostMapping("/{slug}/reject")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void reject(@PathVariable String slug, @AuthenticationPrincipal Jwt admin) {
        find(slug);
        repo.setStatus(slug, "REJECTED");
        audit.record(admin.getSubject(), "REJECT_PROBLEM", slug);
    }

    @PostMapping("/{slug}/retire")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void retire(@PathVariable String slug, @AuthenticationPrincipal Jwt admin) {
        find(slug);
        repo.retire(slug);
        catalog.invalidate();
        audit.record(admin.getSubject(), "RETIRE_PROBLEM", slug);
    }

    private ProblemRecord find(String slug) {
        return repo.find(slug).orElseThrow(() -> ApiException.notFound("problem"));
    }
}