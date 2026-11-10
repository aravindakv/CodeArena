package dev.codearena.problem.domain;

import java.util.List;

/** One row of the problem list. */
public record ProblemSummary(int number, String slug, String title, String difficulty, List<String> tags,
                             double acceptance) {
}