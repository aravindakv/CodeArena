"""Pipeline logic with the mock LLM and an in-process stand-in for the sandbox (no Docker, no AWS needed).
The real sandbox is covered by judge-worker's red-team test; this checks the verification rules."""
import json
import subprocess
import sys
import tempfile
from pathlib import Path

import pytest

from codearena_ingest.compare import equal
from codearena_ingest.examples import example_to_input, split_top_level
from codearena_ingest.llm import MockLLM, extract_json
from codearena_ingest.pipeline import Pipeline, VerificationError
from codearena_ingest.rag import hash_embed
from codearena_ingest.sandbox import GENERATOR_HARNESS, Run

PY_HARNESS = """import sys as ca_sys, json as ca_json
from typing import *
ca_res = open("/out/results.jsonl", "w", buffering=1)
exec(compile(open("/work/solution.py").read(), "solution.py", "exec"))
for ca_line in ca_sys.stdin:
    if ca_line.strip():
        ca_a = ca_json.loads(ca_line)
        ca_res.write(ca_json.dumps(Solution().{fn}(*[ca_a[k] for k in {names}]), separators=(",", ":")) + "\\n")
"""


class LocalSandbox:
    """TEST ONLY: runs trusted test code in a subprocess, rewriting /work and /out to a temp dir."""
    def codegen(self, sig, lang):
        return {"harness": PY_HARNESS.format(fn=sig["function"], names=[p["name"] for p in sig["params"]]), "starter": ""}

    def run(self, lang, code, harness, inputs, **_):
        with tempfile.TemporaryDirectory() as d:
            d = Path(d)
            (d / "solution.py").write_text(code)
            h = harness.replace("/work/", f"{d}/").replace("/out/", f"{d}/")
            p = subprocess.run([sys.executable, "-c", h], input="\n".join(inputs) + "\n", text=True, capture_output=True, timeout=60)
            res = (d / "results.jsonl").read_text().splitlines() if (d / "results.jsonl").exists() else []
            return Run(p.returncode == 0 and len(res) == len(inputs), res, p.stderr[-500:])

    def generate_inputs(self, generator_py, plan):
        return self.run("python", generator_py, GENERATOR_HARNESS, [json.dumps({"seed": s, "size": z}) for s, z in plan])


def test_example_parsing_matches_server_rules():
    assert split_top_level('s = "a,b", k = [1,[2,3]]') == ['s = "a,b"', ' k = [1,[2,3]]']
    assert example_to_input("nums = [3,1], target = 4", ["nums", "target"]) == {"nums": [3, 1], "target": 4}
    with pytest.raises(ValueError):
        example_to_input("x = 1", ["nums", "target"])


def test_compare_modes():
    assert equal("JSON", "[1,2]", "[1.0,2]")
    assert not equal("JSON", "true", "1")
    assert equal("UNORDERED_ARRAY", "[[1],[2,3]]", "[[2,3],[1]]")
    assert equal("FLOAT_1E6", "0.3333333", "0.33333334")
    assert not equal("JSON", "[1]", "Traceback")


def test_hash_embeddings_detect_near_duplicates():
    a = hash_embed("Count lanterns above k times the smallest value in the array")
    b = hash_embed("Count lanterns above k times the smallest value of the array")
    c = hash_embed("Shortest path in a grid where you may break walls")
    dot = lambda x, y: sum(i * j for i, j in zip(x, y))
    assert dot(a, b) > 0.75 > dot(a, c)


def test_extract_json_tolerates_fences():
    assert extract_json('Here:\n```json\n{"a": 1}\n```') == {"a": 1}


def test_mock_problem_verifies_end_to_end():
    p = MockLLM().generate_problem({"id": "007", "topic": "sliding window", "difficulty": "EASY"}, [], None)
    cases = Pipeline(MockLLM(), LocalSandbox(), None, None).verify(p)
    assert len(cases) == 2 + 8 + 24 + 6
    assert cases[0] == {"input": {"values": json.loads(p.examples[0].input.split("=", 1)[1])}, "output": 1}


def test_wrong_example_is_rejected():
    p = MockLLM().generate_problem({"id": "008", "topic": "math", "difficulty": "EASY"}, [], None)
    p.examples[0].output = "42"
    with pytest.raises(VerificationError, match="states output 42"):
        Pipeline(MockLLM(), LocalSandbox(), None, None).verify(p)


def test_buggy_reference_is_caught_by_brute_force():
    p = MockLLM().generate_problem({"id": "009", "topic": "math", "difficulty": "EASY"}, [], None)
    # off-by-one (>= instead of >) that neither example reveals; only the cross-check can find it
    p.reference_py = p.reference_py.replace(" > ", " >= ")
    p.examples = [type(p.examples[0])(input="values = [5,5]", output="0"),
                  type(p.examples[0])(input="values = [1000,1001]", output="0")]
    p.generator_py = "def gen(rng, size):\n    return {'values': [rng.randint(1, 20) for _ in range(rng.randint(2, 30))]}\n"
    with pytest.raises(VerificationError, match="disagree"):
        Pipeline(MockLLM(), LocalSandbox(), None, None).verify(p)


class FakeResponses:
    """Stands in for client.responses: records the request and replies with a canned answer."""
    def __init__(self, text, status="completed"):
        self.text, self.status, self.calls = text, status, []

    def create(self, **kwargs):
        self.calls.append(kwargs)
        return type("Res", (), {"output_text": self.text, "status": self.status, "incomplete_details": None})()


class FakeOpenAI:
    def __init__(self, text, status="completed"):
        self.responses = FakeResponses(text, status)


def test_openai_provider_uses_json_mode_and_validates():
    from codearena_ingest.llm import OpenAILLM
    seed = {"id": "007", "topic": "arrays", "difficulty": "EASY"}
    canned = MockLLM().generate_problem(seed, [], None).model_dump_json()
    client = FakeOpenAI(canned)
    llm = OpenAILLM(model="gpt-4o-mini", reasoning_effort="", client=client)
    problem = llm.generate_problem(seed, [], None)
    call = client.responses.calls[0]
    assert problem.signature.function == "countAbove"
    assert call["model"] == "gpt-4o-mini" and call["text"] == {"format": {"type": "json_object"}}
    assert "reasoning" not in call and call["store"] is False
    assert "JSON" in call["input"]          # JSON mode requires the word JSON in the prompt


def test_openai_reasoning_effort_only_when_set():
    from codearena_ingest.llm import OpenAILLM
    client = FakeOpenAI("```python\nprint(1)\n```")
    OpenAILLM(model="gpt-6.1-sol", reasoning_effort="low", client=client).translate("x", {}, "go", "")
    assert client.responses.calls[0]["reasoning"] == {"effort": "low"}
    assert "text" not in client.responses.calls[0]   # code answers are plain text


def test_openai_incomplete_answer_is_a_clear_failure():
    from codearena_ingest.llm import OpenAILLM
    llm = OpenAILLM(model="gpt-4o-mini", reasoning_effort="", client=FakeOpenAI("{", status="incomplete"))
    with pytest.raises(ValueError, match="stopped early"):
        llm.generate_problem({"id": "1", "topic": "t", "difficulty": "EASY"}, [], None)


def test_openai_embeddings_request_384_dimensions():
    from codearena_ingest.rag import DIM, Embedder

    class FakeEmbeddings:
        def create(self, **kwargs):
            self.kwargs = kwargs
            return type("R", (), {"data": [type("D", (), {"embedding": [0.1] * kwargs["dimensions"]})()]})()

    fake = type("C", (), {"embeddings": FakeEmbeddings()})()
    vec = Embedder("openai", client=fake).embed("two sum")
    assert len(vec) == DIM and fake.embeddings.kwargs["dimensions"] == DIM