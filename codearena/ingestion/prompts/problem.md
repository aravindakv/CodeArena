Write one new problem.

Topic: {{topic}}
Difficulty: {{difficulty}}

Similar problems already on the platform (for style and difficulty calibration only; your problem must be clearly
different from all of them):
{{neighbours}}

Requirements:
- `signature.params[].type` and `signature.returns` must be one of: int, long, double, bool, string, int[], long[],
  double[], string[], int[][]. Use `long` when results can exceed 2^31. Choose `compare`: JSON (exact values),
  UNORDERED_ARRAY (any order accepted), FLOAT_1E6 (real numbers), EXACT (rarely needed).
- If several answers could be valid, either define a tie-breaking rule in the statement or use UNORDERED_ARRAY.
- `reference_py`: an optimal, correct `class Solution` whose method name equals `signature.function` and whose
  parameters are named exactly as in `signature.params`.
- `brute_force_py`: the same method implemented in the most obviously correct way, even if slow. It is only run on
  small inputs and is used to cross-check the reference.
- `generator_py`: defines `gen(rng, size)` returning a dict with exactly the parameter names as keys. `rng` is a
  `random.Random`; `size` is "edge" (smallest and boundary cases), "small" (brute force must finish quickly) or
  "large" (up to the constraint limits, so slow solutions time out). Inputs must always satisfy the constraints.
- 2 to 4 examples, 1 to 3 hints, 1 to 4 lowercase tags.

{{feedback}}

Return ONLY a JSON object that validates against this JSON Schema:
{{schema}}