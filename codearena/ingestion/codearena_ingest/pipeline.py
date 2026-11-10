
"""
One seed -> one verified problem in the review queue.

  retrieve similar problems (RAG)  ->  generate (LLM, structured JSON)  ->  dedupe (vector similarity)
  ->  generate test inputs (LLM-written generator, run in the sandbox)  ->  expected outputs (reference, sandbox)
  ->  cross-check reference vs brute force on small inputs  ->  check the statement's examples
  ->  optional: translate the reference to other languages and run the examples there
  ->  save as IN_REVIEW + add to the vector store
A verification failure goes back to the LLM as feedback (up to GENERATION_ATTEMPTS times).
"""
import json
import logging

from .compare import equal
from .config import CONFIG
from .examples import example_to_input
from .models import GeneratedProblem
from .rag import VectorStore, problem_text

log = logging.getLogger("ingest")

# How many generated tests of each size (the statement's examples come first).
TEST_PLAN = [("edge", 8), ("small", 24), ("large", 6)]


class VerificationError(Exception):
    pass


class Duplicate(Exception):
    def __init__(self, slug: str, similarity: float):
        super().__init__(f"too similar to existing problem '{slug}' (similarity {similarity:.2f})")
        self.slug = slug


class Pipeline:
    def __init__(self, llm, sandbox, store, vectors: VectorStore):
        self.llm, self.sandbox, self.store, self.vectors = llm, sandbox, store, vectors

    def process(self, run_id: str, seed: dict) -> str:
        self.store.stage(run_id, seed["id"], "GENERATING")
        neighbours = self.vectors.nearest(f"{seed['topic']} {seed['difficulty']} programming problem", 3)
        feedback = None
        for attempt in range(1, CONFIG.max_attempts + 1):
            problem = self.llm.generate_problem(seed, neighbours, feedback)
            problem.slug = self.store.unique_slug(problem.slug)
            top = self.vectors.nearest(problem_text(problem.title, problem.statement, problem.tags), 1)
            if top and top[0]["similarity"] >= CONFIG.duplicate_threshold:
                raise Duplicate(top[0]["slug"], top[0]["similarity"])
            self.store.stage(run_id, seed["id"], "VERIFYING", slug=problem.slug)
            try:
                cases = self.verify(problem)
            except VerificationError as e:
                feedback = str(e)
                log.info("seed %s attempt %d failed verification: %s", seed["id"], attempt, e)
                continue
            self.store.save_for_review(problem, cases, run_id)
            doc = problem.doc()
            self.vectors.add(problem.slug, problem.title, problem.difficulty, problem.statement, doc["tags"])
            self.store.stage(run_id, seed["id"], "READY_FOR_REVIEW", slug=problem.slug)
            return problem.slug
        raise VerificationError(f"gave up after {CONFIG.max_attempts} attempts: {feedback}")

    def verify(self, p: GeneratedProblem) -> list[dict]:
        sig = p.signature.model_dump()
        names = [x.name for x in p.signature.params]
        mode = p.signature.compare

        try:
            example_inputs = [json.dumps(example_to_input(e.input, names), separators=(",", ":")) for e in p.examples]
        except (ValueError, json.JSONDecodeError) as e:
            raise VerificationError(f"example inputs are not valid: {e}")

        plan, n = [], 0
        for size, count in TEST_PLAN:
            for _ in range(count):
                plan.append((n, size))
                n += 1
        gen = self.sandbox.generate_inputs(p.generator_py, plan)
        if not gen.ok:
            raise VerificationError(f"the test generator failed: {gen.error}")
        generated = []
        for line in gen.results:
            obj = json.loads(line)
            if not isinstance(obj, dict) or sorted(obj) != sorted(names):
                raise VerificationError(f"generator must return a dict with keys {names}, got {line[:200]}")
            generated.append(json.dumps(obj, separators=(",", ":")))
        inputs = example_inputs + generated

        harness = self.sandbox.codegen(sig, "python")["harness"]
        ref = self.sandbox.run("python", p.reference_py, harness, inputs)
        if not ref.ok:
            raise VerificationError(f"the reference solution failed: {ref.error}")

        for e, out in zip(p.examples, ref.results):
            if not equal(mode, e.output, out):
                raise VerificationError(f"example '{e.input}' states output {e.output} but the reference returns {out}")

        small = len(example_inputs) + sum(c for s, c in TEST_PLAN if s != "large")
        brute = self.sandbox.run("python", p.brute_force_py, harness, inputs[:small])
        if not brute.ok:
            raise VerificationError(f"the brute-force solution failed: {brute.error}")
        mismatches = [i for i, (a, b) in enumerate(zip(ref.results[:small], brute.results)) if not equal(mode, b, a)]
        if mismatches:
            i = mismatches[0]
            raise VerificationError(f"reference and brute force disagree on {len(mismatches)} of {small} inputs, "
                                    f"e.g. {inputs[i][:200]}: {ref.results[i]} vs {brute.results[i]}")

        for lang in CONFIG.verify_langs:
            if lang == "python":
                continue
            gen_code = self.sandbox.codegen(sig, lang)
            code = self.llm.translate(p.reference_py, sig, lang, gen_code["starter"])
            run = self.sandbox.run(lang, code, gen_code["harness"], example_inputs)
            if not run.ok or not all(equal(mode, e.output, o) for e, o in zip(p.examples, run.results)):
                raise VerificationError(f"the {lang} harness/translation failed on the examples: {run.error or run.results}")

        return [{"input": json.loads(i), "output": json.loads(o)} for i, o in zip(inputs, ref.results)]