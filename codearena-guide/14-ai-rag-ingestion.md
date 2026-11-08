# 14 · Admin-only AI + RAG problem ingestion

An admin starts a run in the console (topics, difficulties, how many). problem-service records the run and puts
one message per seed on `ingestion-jobs`. This Python worker turns each seed into a verified, original problem and
leaves it **in review**. Nothing reaches users until an admin publishes it.

## The pipeline for one seed

| Stage | What happens | Why |
| --- | --- | --- |
| Retrieve (RAG) | embed "topic + difficulty", fetch the 3 nearest existing problems from pgvector | gives the model the house style and difficulty level, and what not to repeat |
| Generate | the LLM returns JSON matching a schema: statement, examples, constraints, signature, reference solution, brute force, test generator | structured output is validated by pydantic before anything else |
| Deduplicate | embed the new statement; cosine similarity ≥ 0.92 with any existing problem → DUPLICATE | keeps the bank free of near-copies |
| Generate tests | run the LLM's `gen(rng, size)` in the sandbox: 8 edge, 24 small, 6 large inputs | the model writes inputs only; it never writes expected outputs |
| Compute outputs | run the reference solution on every input, in the sandbox | outputs come from executed code |
| Verify examples | the statement's own examples must match the reference | catches wrong examples, the most common LLM error |
| Cross-check | run the brute force on the small inputs; it must agree with the reference | catches subtle reference bugs |
| Other languages (optional) | translate the reference to Java, C++, JS, Go and run the examples through each language's real harness | proves the signature works everywhere |
| Review | save as IN_REVIEW with tests v1, add to the vector store | the admin reads it and publishes or rejects |

A failed verification is fed back to the model ("example 2 states 5 but the reference returns 4") for a second
attempt. All LLM-written code runs in the same sandbox as user code: it is untrusted input too.

With 30 built-in topics × 3 difficulties, a few runs of 200 seeds produce your first thousand problems. Budget
about 3 to 6 model calls per problem (more with `VERIFY_LANGS` beyond Python) and expect 10–30% to fail
verification; that is the pipeline doing its job.

**Providers.** `LLM_PROVIDER=mock` (the default) needs no key and produces simple, correct, distinct problems, so you
can run every stage offline. `LLM_PROVIDER=anthropic` with `ANTHROPIC_API_KEY` uses Claude.
**Embeddings.** `EMBEDDINGS=hash` (default, no download) is enough to catch near-duplicates; `EMBEDDINGS=fastembed`
gives real semantic search (install with `uv sync --extra embeddings`, downloads a ~130 MB model once).

## Files

**File:** `ingestion/pyproject.toml`

```toml
[project]
name = "codearena-ingest"
version = "0.1.0"
description = "Admin-only AI + RAG pipeline that turns topic seeds into verified, original CodeArena problems"
requires-python = ">=3.11"
dependencies = [
  "anthropic>=0.69",
  "boto3>=1.35",
  "pydantic>=2.8",
  "psycopg[binary]>=3.2",
  "pgvector>=0.3.6",
  "numpy>=1.26",
  "requests>=2.32",
]

[project.optional-dependencies]
# Real sentence embeddings (downloads a ~130 MB model on first use). Without it EMBEDDINGS=hash is used.
embeddings = ["fastembed>=0.4"]
dev = ["pytest>=8"]

[project.scripts]
codearena-ingest = "codearena_ingest.worker:main"

[build-system]
requires = ["hatchling"]
build-backend = "hatchling.build"

[tool.hatch.build.targets.wheel]
packages = ["codearena_ingest"]
```

**File:** `ingestion/README.md`

````markdown
# CodeArena ingestion worker (admin-only AI + RAG)

Started from the admin console (AI ingestion), which queues one SQS message per seed. This worker turns each seed
into a verified, original problem and leaves it in the review queue. See guide chapter 13.

```bash
cd ingestion
uv sync --extra dev                       # or: python3 -m venv .venv && .venv/bin/pip install -e '.[dev]'
uv run pytest -q                          # logic tests, no Docker/AWS needed
uv run codearena-ingest                   # the worker (needs scripts/up.sh, problem-service, runner images)
```

Python 3.11+ (3.13 recommended). Settings come from the repo's `.env`: `LLM_PROVIDER=mock|anthropic`, `ANTHROPIC_API_KEY`, `LLM_MODEL`,
`EMBEDDINGS=hash|fastembed` (fastembed needs `uv sync --extra embeddings`), `VERIFY_LANGS=python,java,cpp,javascript,go`.
````

**File:** `ingestion/codearena_ingest/__init__.py`

```python
"""CodeArena AI + RAG ingestion worker (admin-triggered)."""
```

**File:** `ingestion/codearena_ingest/__main__.py`

```python
from .worker import main

raise SystemExit(main())
```

**File:** `ingestion/codearena_ingest/config.py`

```python
import os
from dataclasses import dataclass, field
from pathlib import Path


def _load_dotenv():
    """Read the repo's .env (KEY=VALUE lines) without overriding real environment variables."""
    for candidate in (Path.cwd() / ".env", Path(__file__).resolve().parents[2] / ".env"):
        if candidate.is_file():
            for line in candidate.read_text().splitlines():
                line = line.strip()
                if line and not line.startswith("#") and "=" in line:
                    k, v = line.split("=", 1)
                    os.environ.setdefault(k.strip(), v.strip())
            return


_load_dotenv()


@dataclass(frozen=True)
class Config:
    region: str = os.environ.get("AWS_REGION", "ap-south-1")
    access_key: str = os.environ.get("AWS_ACCESS_KEY_ID", "local")
    secret_key: str = os.environ.get("AWS_SECRET_ACCESS_KEY", "local")
    dynamo_endpoint: str = os.environ.get("DYNAMO_ENDPOINT", "http://localhost:9700")
    s3_endpoint: str = os.environ.get("S3_ENDPOINT", "http://localhost:9703")
    sqs_endpoint: str = os.environ.get("SQS_ENDPOINT", "http://localhost:9702")
    pg_url: str = os.environ.get("PG_URL", "postgresql://codearena:codearena@localhost:9704/codearena")
    problem_url: str = os.environ.get("PROBLEM_URL", "http://localhost:9082")
    queue: str = os.environ.get("INGESTION_QUEUE", "ingestion-jobs")
    tests_bucket: str = os.environ.get("TESTS_BUCKET", "codearena-tests")

    llm_provider: str = os.environ.get("LLM_PROVIDER", "mock")          # "anthropic" or "mock"
    llm_model: str = os.environ.get("LLM_MODEL", "claude-sonnet-5-5")
    embeddings: str = os.environ.get("EMBEDDINGS", "hash")              # "fastembed" or "hash"
    judge_runtime: str = os.environ.get("JUDGE_RUNTIME", "runsc")
    work_root: str = os.environ.get("INGEST_WORK_ROOT", "/tmp/codearena-ingest")
    verify_langs: list[str] = field(default_factory=lambda: [
        x.strip() for x in os.environ.get("VERIFY_LANGS", "python").split(",") if x.strip()])
    duplicate_threshold: float = float(os.environ.get("DUPLICATE_THRESHOLD", "0.92"))
    max_attempts: int = int(os.environ.get("GENERATION_ATTEMPTS", "2"))


CONFIG = Config()
```

**File:** `ingestion/codearena_ingest/models.py`

```python
"""The generated problem. Mirrors ProblemDoc in problem-service, plus the code the LLM must provide."""
import re
from typing import Literal, Optional

from pydantic import BaseModel, Field, field_validator

TYPES = ("int", "long", "double", "bool", "string", "int[]", "long[]", "double[]", "string[]", "int[][]")


class Param(BaseModel):
    name: str = Field(pattern=r"^[a-zA-Z][a-zA-Z0-9_]*$")
    type: Literal[TYPES]  # type: ignore[valid-type]

    @field_validator("name")
    @classmethod
    def not_reserved(cls, v: str) -> str:
        if v.startswith("ca_"):
            raise ValueError("parameter names may not start with ca_ (reserved by the harness)")
        return v


class Signature(BaseModel):
    function: str = Field(pattern=r"^[a-zA-Z_][a-zA-Z0-9_]*$")
    params: list[Param] = Field(min_length=1, max_length=4)
    returns: Literal[TYPES]  # type: ignore[valid-type]
    compare: Literal["EXACT", "JSON", "UNORDERED_ARRAY", "FLOAT_1E6"] = "JSON"


class Example(BaseModel):
    input: str
    output: str
    explanation: Optional[str] = None


class GeneratedProblem(BaseModel):
    slug: str = Field(pattern=r"^[a-z0-9]+(-[a-z0-9]+)*$", max_length=60)
    title: str = Field(min_length=3, max_length=80)
    difficulty: Literal["EASY", "MEDIUM", "HARD"]
    tags: list[str] = Field(min_length=1, max_length=5)
    statement: str = Field(min_length=40)
    constraints: list[str] = Field(min_length=1)
    examples: list[Example] = Field(min_length=2, max_length=4)
    signature: Signature
    hints: list[str] = Field(default_factory=list)
    reference_py: str          # class Solution with the optimal method
    brute_force_py: str        # class Solution with an obviously correct slow method
    generator_py: str          # def gen(rng, size) -> dict of inputs; size in {"edge","small","large"}

    def doc(self) -> dict:
        """The ProblemDoc JSON stored by problem-service (no code)."""
        return {
            "slug": self.slug, "title": self.title, "difficulty": self.difficulty,
            "tags": [re.sub(r"[^a-z0-9-]+", "-", t.lower()).strip("-") for t in self.tags],
            "statement": self.statement, "constraints": self.constraints,
            "examples": [e.model_dump(exclude_none=True) for e in self.examples],
            "signature": self.signature.model_dump(), "limits": {"timeMs": 2000, "memoryMb": 256},
            "hints": self.hints,
        }
```

**File:** `ingestion/codearena_ingest/examples.py`

```python
import json


def split_top_level(s: str) -> list[str]:
    """Split on commas outside brackets and quotes (same rule as problem-service's ExampleInputs)."""
    out, depth, in_str, cur = [], 0, False, []
    for i, c in enumerate(s):
        if c == '"' and (i == 0 or s[i - 1] != "\\"):
            in_str = not in_str
        if not in_str:
            if c in "[{":
                depth += 1
            elif c in "]}":
                depth -= 1
            elif c == "," and depth == 0:
                out.append("".join(cur))
                cur = []
                continue
        cur.append(c)
    if "".join(cur).strip():
        out.append("".join(cur))
    return out


def example_to_input(text: str, param_names: list[str]) -> dict:
    """'nums = [1,2], target = 3' -> {"nums": [1, 2], "target": 3}"""
    parts = split_top_level(text)
    if len(parts) != len(param_names):
        raise ValueError(f"example input has {len(parts)} values, signature has {len(param_names)}: {text}")
    out = {}
    for part, name in zip(parts, param_names):
        key, eq, value = part.partition("=")
        if eq and key.strip() != name:
            raise ValueError(f"example names '{key.strip()}' where the signature has '{name}'")
        out[name] = json.loads(value.strip() if eq else part.strip())
    return out
```

**File:** `ingestion/codearena_ingest/compare.py`

```python
"""Output comparison, identical in meaning to judge-worker's Comparators.java."""
import json


def _same(e, a) -> bool:
    if isinstance(e, bool) or isinstance(a, bool):
        return e is a or e == a and type(e) is type(a)
    if isinstance(e, (int, float)) and isinstance(a, (int, float)):
        return e == a
    if isinstance(e, list) and isinstance(a, list):
        return len(e) == len(a) and all(_same(x, y) for x, y in zip(e, a))
    if isinstance(e, dict) and isinstance(a, dict):
        return e.keys() == a.keys() and all(_same(e[k], a[k]) for k in e)
    return e == a


def _close(e, a) -> bool:
    if isinstance(e, (int, float)) and isinstance(a, (int, float)) and not isinstance(e, bool):
        d = abs(e - a)
        return d <= 1e-6 or d <= 1e-6 * max(abs(e), abs(a))
    if isinstance(e, list) and isinstance(a, list):
        return len(e) == len(a) and all(_close(x, y) for x, y in zip(e, a))
    return _same(e, a)


def equal(mode: str, expected: str, actual: str) -> bool:
    if mode == "EXACT":
        return expected.strip() == actual.strip()
    try:
        e, a = json.loads(expected), json.loads(actual)
    except (ValueError, TypeError):
        return False
    if mode == "UNORDERED_ARRAY" and isinstance(e, list) and isinstance(a, list):
        key = lambda x: json.dumps(x, sort_keys=True)
        return len(e) == len(a) and _same(sorted(e, key=key), sorted(a, key=key))
    if mode == "FLOAT_1E6":
        return _close(e, a)
    return _same(e, a)
```

**File:** `ingestion/codearena_ingest/rag.py`

```python
"""
Retrieval over the existing problem bank (pgvector).
Used twice per seed: (1) fetch similar problems as style/difficulty examples and "do not duplicate" context
for the LLM, (2) reject a generated problem that is too similar to one we already have.
"""
import hashlib
import math
import re

import psycopg
from pgvector.psycopg import register_vector

from .config import CONFIG

DIM = 384


class Embedder:
    def __init__(self, kind: str = CONFIG.embeddings):
        self.kind = kind
        self._model = None
        if kind == "fastembed":
            from fastembed import TextEmbedding   # optional dependency
            self._model = TextEmbedding("BAAI/bge-small-en-v1.5")   # 384 dimensions

    def embed(self, text: str) -> list[float]:
        if self._model is not None:
            return [float(x) for x in next(iter(self._model.embed([text])))]
        return hash_embed(text)


def hash_embed(text: str) -> list[float]:
    """Offline fallback: hashed bag of words and word pairs, L2-normalised. Good enough to catch near-duplicates."""
    words = re.findall(r"[a-z0-9]+", text.lower())
    vec = [0.0] * DIM
    for token in words + [a + " " + b for a, b in zip(words, words[1:])]:
        h = int(hashlib.md5(token.encode()).hexdigest(), 16)
        vec[h % DIM] += 1.0 if (h >> 9) & 1 else -1.0
    norm = math.sqrt(sum(v * v for v in vec)) or 1.0
    return [v / norm for v in vec]


def problem_text(title: str, statement: str, tags: list[str]) -> str:
    return f"{title}\n{' '.join(tags)}\n{statement}"


class VectorStore:
    def __init__(self, url: str = CONFIG.pg_url, embedder: Embedder | None = None):
        self.conn = psycopg.connect(url, autocommit=True)
        self.conn.execute("CREATE EXTENSION IF NOT EXISTS vector")
        register_vector(self.conn)
        self.conn.execute(f"""
            CREATE TABLE IF NOT EXISTS problem_vectors (
              slug text PRIMARY KEY, title text NOT NULL, difficulty text NOT NULL, statement text NOT NULL,
              embedding vector({DIM}) NOT NULL)""")
        self.conn.execute("CREATE INDEX IF NOT EXISTS problem_vectors_hnsw ON problem_vectors "
                          "USING hnsw (embedding vector_cosine_ops)")
        self.embedder = embedder or Embedder()

    def known_slugs(self) -> set[str]:
        return {r[0] for r in self.conn.execute("SELECT slug FROM problem_vectors").fetchall()}

    def add(self, slug: str, title: str, difficulty: str, statement: str, tags: list[str]):
        import numpy as np
        vec = np.array(self.embedder.embed(problem_text(title, statement, tags)), dtype=np.float32)
        self.conn.execute(
            "INSERT INTO problem_vectors (slug, title, difficulty, statement, embedding) VALUES (%s,%s,%s,%s,%s) "
            "ON CONFLICT (slug) DO UPDATE SET title = EXCLUDED.title, statement = EXCLUDED.statement, "
            "embedding = EXCLUDED.embedding", (slug, title, difficulty, statement, vec))

    def nearest(self, text: str, k: int = 3) -> list[dict]:
        import numpy as np
        vec = np.array(self.embedder.embed(text), dtype=np.float32)
        rows = self.conn.execute(
            "SELECT slug, title, difficulty, statement, 1 - (embedding <=> %s) AS sim "
            "FROM problem_vectors ORDER BY embedding <=> %s LIMIT %s", (vec, vec, k)).fetchall()
        return [{"slug": r[0], "title": r[1], "difficulty": r[2], "statement": r[3], "similarity": float(r[4])} for r in rows]
```

**File:** `ingestion/codearena_ingest/llm.py`

````python
"""
LLM providers. "anthropic" calls the Claude API; "mock" returns deterministic canned problems so the whole
pipeline can be run and tested offline without an API key.
"""
import json
import random
import re
from pathlib import Path

from .config import CONFIG
from .models import GeneratedProblem

PROMPTS = Path(__file__).resolve().parent.parent / "prompts"


def render(name: str, **values) -> str:
    text = (PROMPTS / name).read_text()
    for k, v in values.items():
        text = text.replace("{{" + k + "}}", v if isinstance(v, str) else json.dumps(v, indent=2))
    return text


def extract_json(text: str) -> dict:
    """The model is told to answer with JSON only; tolerate a code fence or stray prose around it."""
    fenced = re.search(r"```(?:json)?\s*(\{.*\})\s*```", text, re.S)
    raw = fenced.group(1) if fenced else text[text.index("{"): text.rindex("}") + 1]
    return json.loads(raw)


def extract_code(text: str) -> str:
    fenced = re.search(r"```[a-zA-Z+]*\s*\n(.*?)```", text, re.S)
    return (fenced.group(1) if fenced else text).strip() + "\n"


class AnthropicLLM:
    def __init__(self, model: str = CONFIG.llm_model):
        import anthropic
        self.client = anthropic.Anthropic()   # reads ANTHROPIC_API_KEY
        self.model = model

    def _ask(self, system: str, user: str, max_tokens: int = 8000) -> str:
        msg = self.client.messages.create(model=self.model, max_tokens=max_tokens, system=system,
                                          messages=[{"role": "user", "content": user}])
        return "".join(b.text for b in msg.content if getattr(b, "type", "") == "text")

    def generate_problem(self, seed: dict, neighbours: list[dict], feedback: str | None) -> GeneratedProblem:
        user = render("problem.md", topic=seed["topic"], difficulty=seed["difficulty"],
                      neighbours=[{k: n[k] for k in ("title", "difficulty", "statement")} for n in neighbours],
                      schema=GeneratedProblem.model_json_schema(),
                      feedback=f"Your previous attempt failed verification: {feedback}\nFix it." if feedback else "")
        return GeneratedProblem.model_validate(extract_json(self._ask(render("system.md"), user)))

    def translate(self, reference_py: str, signature: dict, lang: str, starter: str) -> str:
        user = render("translate.md", lang=lang, reference=reference_py, starter=starter, signature=signature)
        return extract_code(self._ask(render("system.md"), user, 4000))


class MockLLM:
    """Offline stand-in: a family of simple, correct, distinct problems. Exercises every pipeline stage."""
    def generate_problem(self, seed: dict, neighbours: list[dict], feedback: str | None) -> GeneratedProblem:
        rng = random.Random(seed["topic"] + seed["difficulty"] + seed.get("id", ""))
        k = rng.randint(2, 9)
        word = rng.choice(["lanterns", "parcels", "badges", "tickets", "seedlings", "beacons", "pebbles"])
        topic_slug = re.sub(r"[^a-z0-9]+", "-", seed["topic"].lower()).strip("-")
        return GeneratedProblem(
            slug=f"{word}-above-{k}-{topic_slug}-{seed.get('id', '0')}",
            title=f"Count {word.title()} Above {k}",
            difficulty=seed["difficulty"],
            tags=[topic_slug, "counting"],
            statement=(f"A row of {word} is described by the integer array `values`. Count how many {word} have a "
                       f"value strictly greater than {k} times the smallest value in the array.\n\n"
                       f"(Generated offline by the mock provider for topic: {seed['topic']}.)"),
            constraints=["1 <= values.length <= 10^5", "1 <= values[i] <= 10^4"],
            examples=[{"input": f"values = [1,{k + 1},{k}]", "output": "1"},
                      {"input": "values = [5,5]", "output": "0"}],
            signature={"function": "countAbove", "params": [{"name": "values", "type": "int[]"}],
                       "returns": "int", "compare": "JSON"},
            hints=["Find the minimum first."],
            reference_py=(f"class Solution:\n    def countAbove(self, values):\n        m = min(values)\n"
                          f"        return sum(1 for v in values if v > {k} * m)\n"),
            brute_force_py=(f"class Solution:\n    def countAbove(self, values):\n"
                            f"        smallest = sorted(values)[0]\n"
                            f"        return len([v for v in values if v > {k} * smallest])\n"),
            generator_py=("def gen(rng, size):\n    n = {'edge': rng.randint(1, 3), 'small': rng.randint(4, 40), "
                          "'large': rng.randint(50000, 100000)}[size]\n"
                          "    return {'values': [rng.randint(1, 10000) for _ in range(n)]}\n"),
        )

    def translate(self, reference_py: str, signature: dict, lang: str, starter: str) -> str:
        raise NotImplementedError("the mock provider only verifies Python; set VERIFY_LANGS=python")


def make_llm():
    return AnthropicLLM() if CONFIG.llm_provider == "anthropic" else MockLLM()
````

**File:** `ingestion/codearena_ingest/sandbox.py`

```python
"""
Runs LLM-written code (test generators, reference solutions, translations) in the SAME sandbox as user
submissions: the runner images from sandbox-images/, no network, gVisor, hard limits. LLM output is untrusted
code and is never executed on the host.
"""
import json
import os
import shutil
import subprocess
import tempfile
import uuid
from dataclasses import dataclass
from pathlib import Path

import requests

from .config import CONFIG

LANG_FILES = {  # user file, harness file (must match sandbox-images/<lang>/run.sh and Lang.java)
    "python": ("solution.py", "harness.py"), "java": ("Solution.java", "Main.java"), "cpp": ("solution.cpp", "main.cpp"),
    "javascript": ("solution.js", "harness.js"), "go": ("solution.go", "harness.go"),
}
LANG_TMPFS = {"python": "rw,noexec,nosuid,size=64m", "javascript": "rw,noexec,nosuid,size=64m",
              "java": "rw,noexec,nosuid,size=128m", "cpp": "rw,exec,nosuid,size=128m", "go": "rw,exec,nosuid,size=512m"}
LANG_EXTRA_MB = {"python": 0, "javascript": 64, "java": 320, "cpp": 512, "go": 768}

# Harness for test-input generators: reads {"seed": n, "size": "..."} lines, writes gen() output as JSON lines.
GENERATOR_HARNESS = """import sys as ca_sys, json as ca_json, random as ca_random
ca_res = open("/out/results.jsonl", "w", buffering=1)
exec(compile(open("/work/solution.py").read(), "generator.py", "exec"))
for ca_line in ca_sys.stdin:
    if ca_line.strip():
        ca_a = ca_json.loads(ca_line)
        ca_res.write(ca_json.dumps(gen(ca_random.Random(ca_a["seed"]), ca_a["size"]), separators=(",", ":")) + "\\n")
"""


@dataclass
class Run:
    ok: bool
    results: list[str]
    error: str


class Sandbox:
    def __init__(self, runtime: str = CONFIG.judge_runtime, work_root: str = CONFIG.work_root):
        self.runtime = runtime
        self.work_root = Path(work_root)
        self.work_root.mkdir(parents=True, exist_ok=True)

    def codegen(self, signature: dict, lang: str) -> dict:
        """Starter + harness from problem-service, so verification uses exactly the code the judge uses."""
        r = requests.post(f"{CONFIG.problem_url}/internal/codegen", json={"signature": signature, "lang": lang}, timeout=10)
        r.raise_for_status()
        return r.json()

    def run(self, lang: str, code: str, harness: str, inputs: list[str], time_ms: int = 20000, memory_mb: int = 512) -> Run:
        user_file, harness_file = LANG_FILES[lang]
        job = Path(tempfile.mkdtemp(prefix="ing-", dir=self.work_root))
        work, out = job / "work", job / "out"
        work.mkdir()
        out.mkdir()
        try:
            (work / user_file).write_text(code)
            (work / harness_file).write_text(harness)
            (work / "inputs.jsonl").write_text("\n".join(inputs) + "\n")
            for p in (job, work):
                os.chmod(p, 0o755)
            for f in work.iterdir():
                os.chmod(f, 0o644)
            os.chmod(out, 0o777)
            name = f"ca-ing-{uuid.uuid4().hex[:12]}"
            mem = memory_mb + LANG_EXTRA_MB[lang]
            cmd = ["docker", "run", "--rm", "--name", name, f"--runtime={self.runtime}", "--network=none", "--read-only",
                   "--tmpfs", f"/tmp:{LANG_TMPFS[lang]}", f"--memory={mem}m", f"--memory-swap={mem}m", "--cpus=1",
                   "--pids-limit=128", "--cap-drop=ALL", "--security-opt=no-new-privileges", "--user", "65534:65534",
                   "--ulimit", "nofile=64:64", "--ulimit", "fsize=104857600:104857600",
                   "-e", f"TIME_LIMIT_MS={time_ms}", "-e", "MAX_OUT_BYTES=65536", "-e", f"MEMORY_MB={memory_mb}",
                   "-v", f"{work}:/work:ro", "-v", f"{out}:/out:rw", f"codearena/runner-{lang}:1"]
            try:
                subprocess.run(cmd, capture_output=True, timeout=time_ms / 1000 + 60, check=False)
            except subprocess.TimeoutExpired:
                subprocess.run(["docker", "kill", name], capture_output=True)
                return Run(False, [], "wall-clock timeout")
            meta = (out / "meta").read_text().split() if (out / "meta").exists() else []
            results = [l for l in (out / "results.jsonl").read_text().splitlines() if l.strip()] \
                if (out / "results.jsonl").exists() else []
            if meta[:1] == ["COMPILE"]:
                return Run(False, [], "compile error: " + (out / "compile.err").read_text()[:1500])
            if not meta or meta[1] != "0" or len(results) != len(inputs):
                err = (out / "stderr").read_text()[:1500] if (out / "stderr").exists() else ""
                return Run(False, results, f"exit {meta[1] if len(meta) > 1 else '?'}, {len(results)}/{len(inputs)} results. {err}")
            return Run(True, results, "")
        finally:
            shutil.rmtree(job, ignore_errors=True)

    def generate_inputs(self, generator_py: str, plan: list[tuple[int, str]]) -> Run:
        lines = [json.dumps({"seed": s, "size": size}) for s, size in plan]
        return self.run("python", generator_py, GENERATOR_HARNESS, lines)
```

**File:** `ingestion/codearena_ingest/store.py`

```python
"""DynamoDB/S3 writes: run progress for the admin console, and the generated problem as IN_REVIEW."""
import json
import secrets
from datetime import datetime, timezone

import boto3
from botocore.exceptions import ClientError

from .config import CONFIG
from .models import GeneratedProblem


def _clients():
    creds = dict(region_name=CONFIG.region, aws_access_key_id=CONFIG.access_key, aws_secret_access_key=CONFIG.secret_key)
    return (boto3.client("dynamodb", endpoint_url=CONFIG.dynamo_endpoint, **creds),
            boto3.client("s3", endpoint_url=CONFIG.s3_endpoint, **creds),
            boto3.client("sqs", endpoint_url=CONFIG.sqs_endpoint, **creds))


class Store:
    def __init__(self):
        self.ddb, self.s3, self.sqs = _clients()

    def stage(self, run_id: str, seed_id: str, stage: str, slug: str | None = None, error: str | None = None):
        names, values, sets = {"#st": "stage"}, {":st": {"S": stage}, ":u": {"S": datetime.now(timezone.utc).isoformat()}}, ["#st = :st", "updatedAt = :u"]
        if slug:
            sets.append("slug = :sl"); values[":sl"] = {"S": slug}
        if error:
            sets.append("#err = :e"); names["#err"] = "error"; values[":e"] = {"S": error[:900]}
        self.ddb.update_item(TableName="IngestionRuns", Key={"PK": {"S": f"RUN#{run_id}"}, "SK": {"S": f"SEED#{seed_id}"}},
                             UpdateExpression="SET " + ", ".join(sets), ExpressionAttributeNames=names,
                             ExpressionAttributeValues=values)

    def unique_slug(self, slug: str) -> str:
        candidate = slug
        while "Item" in self.ddb.get_item(TableName="Problems", Key={"PK": {"S": f"PROBLEM#{candidate}"}, "SK": {"S": "META"}}):
            candidate = f"{slug[:52]}-{secrets.token_hex(3)}"
        return candidate

    def save_for_review(self, p: GeneratedProblem, cases: list[dict], run_id: str):
        """Tests to S3 (v1), problem to DynamoDB as IN_REVIEW. Publishing is an admin decision in the console."""
        body = "\n".join(json.dumps(c, separators=(",", ":")) for c in cases) + "\n"
        self.s3.put_object(Bucket=CONFIG.tests_bucket, Key=f"{p.slug}/v1/cases.jsonl", Body=body.encode())
        now = datetime.now(timezone.utc).isoformat()
        doc = p.doc()
        try:
            self.ddb.put_item(TableName="Problems", ConditionExpression="attribute_not_exists(PK)", Item={
                "PK": {"S": f"PROBLEM#{p.slug}"}, "SK": {"S": "META"}, "slug": {"S": p.slug}, "title": {"S": p.title},
                "difficulty": {"S": p.difficulty}, "tags": {"S": ",".join(doc["tags"])}, "doc": {"S": json.dumps(doc)},
                "status": {"S": "IN_REVIEW"}, "testsVersion": {"N": "1"}, "source": {"S": f"ai:{run_id}"},
                "createdAt": {"S": now}, "updatedAt": {"S": now}})
        except ClientError as e:
            if e.response["Error"]["Code"] == "ConditionalCheckFailedException":
                raise ValueError(f"slug {p.slug} was taken concurrently") from e
            raise

    def all_problems(self):
        """Every problem (any status) for syncing the vector store."""
        paginator = self.ddb.get_paginator("scan")
        for page in paginator.paginate(TableName="Problems", FilterExpression="SK = :m",
                                       ExpressionAttributeValues={":m": {"S": "META"}}):
            for item in page["Items"]:
                yield json.loads(item["doc"]["S"])
```

**File:** `ingestion/codearena_ingest/pipeline.py`

```python
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
```

**File:** `ingestion/codearena_ingest/worker.py`

```python
"""
SQS worker. One message per seed from problem-service (POST /v1/admin/ingestion/runs, ADMIN only):
  {"runId": "...", "seed": {"id": "001", "topic": "sliding window", "difficulty": "MEDIUM"}}

    cd ingestion && uv run codearena-ingest          (or: python -m codearena_ingest.worker)
"""
import json
import logging
import signal
import sys

from .config import CONFIG
from .llm import make_llm
from .pipeline import Duplicate, Pipeline, VerificationError
from .rag import VectorStore
from .sandbox import Sandbox
from .store import Store

log = logging.getLogger("ingest")
_running = True


def sync_vectors(store: Store, vectors: VectorStore) -> int:
    """Make sure every existing problem is searchable (RAG context and duplicate detection)."""
    known, added = vectors.known_slugs(), 0
    for doc in store.all_problems():
        if doc["slug"] not in known:
            vectors.add(doc["slug"], doc["title"], doc["difficulty"], doc["statement"], doc.get("tags", []))
            added += 1
    return added


def main():
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
    store, sandbox, vectors = Store(), Sandbox(), VectorStore()
    log.info("LLM provider=%s model=%s embeddings=%s verify=%s", CONFIG.llm_provider, CONFIG.llm_model,
             CONFIG.embeddings, ",".join(CONFIG.verify_langs))
    log.info("vector store: %d problems added", sync_vectors(store, vectors))
    pipeline = Pipeline(make_llm(), sandbox, store, vectors)
    queue_url = store.sqs.get_queue_url(QueueName=CONFIG.queue)["QueueUrl"]

    def stop(*_):
        global _running
        _running = False
        log.info("stopping after the current message")
    signal.signal(signal.SIGINT, stop)
    signal.signal(signal.SIGTERM, stop)

    log.info("waiting for ingestion jobs on %s", CONFIG.queue)
    while _running:
        res = store.sqs.receive_message(QueueUrl=queue_url, MaxNumberOfMessages=1, WaitTimeSeconds=10)
        for m in res.get("Messages", []):
            body = json.loads(m["Body"])
            run_id, seed = body["runId"], body["seed"]
            try:
                slug = pipeline.process(run_id, seed)
                log.info("seed %s/%s -> %s ready for review", run_id, seed["id"], slug)
            except Duplicate as e:
                store.stage(run_id, seed["id"], "DUPLICATE", error=str(e))
            except (VerificationError, ValueError) as e:
                store.stage(run_id, seed["id"], "FAILED", error=str(e))
            except Exception as e:   # infrastructure problem: leave the message for a retry (then the DLQ)
                log.exception("seed %s/%s crashed; it will be retried", run_id, seed["id"])
                store.stage(run_id, seed["id"], "FAILED", error=f"worker error, will retry: {e}")
                continue
            store.sqs.delete_message(QueueUrl=queue_url, ReceiptHandle=m["ReceiptHandle"])
    return 0


if __name__ == "__main__":
    sys.exit(main())
```

The prompts are plain files so you can improve them without touching code:

**File:** `ingestion/prompts/problem.md`

```markdown
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
```

**File:** `ingestion/prompts/system.md`

```markdown
You write programming problems and reference solutions for CodeArena, a practice and contest platform.

Rules that always apply:
- Every problem must be ORIGINAL: invent your own story, names, title and examples. Do not reproduce or lightly
  paraphrase problems from LeetCode, Codeforces, HackerRank, textbooks or any other source.
- Statements are clear, self-contained Markdown: what the input is, what to return, tie-breaking rules, edge cases.
  Use `backticks` for parameter names. No HTML.
- Examples must be correct. Each example input is written as `name = value, name = value` where every value is
  valid JSON (strings in double quotes, booleans true/false). Outputs are valid JSON.
- Constraints must match what the generator produces.
- Code is Python 3.13, standard library only, no input() or print(): solutions are a `class Solution` with one method.
- Answer with exactly what is asked (JSON or a single code block) and nothing else.
```

**File:** `ingestion/prompts/translate.md`

````markdown
Translate this Python reference solution to {{lang}}.

Signature (language-neutral): {{signature}}

Fill in this starter code exactly; keep the class/function name and parameter list unchanged, standard library only,
no main function, no reading input, no printing:
```
{{starter}}
```

Python reference:
```python
{{reference}}
```

Return only the complete {{lang}} code in a single code block.
````

**File:** `ingestion/tests/test_pipeline.py`

````python
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
````

**File:** `scripts/ingest.sh`

```bash
#!/usr/bin/env bash
# Runs the admin AI + RAG ingestion worker (reads .env). Stop with Ctrl+C.
set -euo pipefail
cd "$(dirname "$0")/../ingestion"
if command -v uv >/dev/null 2>&1; then
  uv sync -q && exec uv run codearena-ingest
fi
[[ -x .venv/bin/python ]] || { python3 -m venv .venv && .venv/bin/pip install -q -e '.[dev]'; }
exec .venv/bin/python -m codearena_ingest
```

## Check

```bash
cd ingestion && uv sync --extra dev && uv run pytest -q      # 7 passed (no Docker or AWS needed)
cd .. && scripts/ingest.sh                                     # the worker; leave it running
```

In the web app: Admin → AI ingestion → 5 problems, Easy → Start. Open the run: seeds move through generating and
verifying to ready for review. Open one from the review queue, read it, Publish. It appears in the problem list with
starter code in all five languages; solve it.

Then switch to the real model: set `LLM_PROVIDER=anthropic` and `ANTHROPIC_API_KEY=...` in `.env`, restart the worker,
and start a run of 5 on a topic you know well, so you can judge the quality yourself before scaling up.
