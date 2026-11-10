package dev.codearena.problem.domain;

import dev.codearena.codegen.Signature;
import dev.codearena.common.events.Limits;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.util.List;

/**
 * The full problem as stored in the "doc" attribute (JSON). Hidden test cases are NOT here;
 * they live in S3 at codearena-tests/<slug>/v<testsVersion>/cases.jsonl.
 */
public record ProblemDoc(
        @NotBlank @Pattern(regexp = "[a-z0-9]+(-[a-z0-9]+)*") String slug,
        @NotBlank String title,
        @NotBlank @Pattern(regexp = "EASY|MEDIUM|HARD") String difficulty,
        @NotNull List<String> tags,
        @NotBlank String statement,
        @NotNull List<String> constraints,
        @NotEmpty List<@Valid Example> examples,
        @NotNull Signature signature,
        Limits limits,
        List<String> hints) {

    public record Example(@NotBlank String input, @NotBlank String output, String explanation) {}

    public Limits limitsOrDefault() {
        return limits == null ? Limits.DEFAULT : limits;
    }
}