"""
LLM providers. "openai" calls the OpenAI Responses API, "anthropic" calls the Claude API, and "mock" returns
deterministic canned problems so the whole pipeline can be run and tested offline without an API key.
All real providers share the same prompts and the same pydantic validation; only _ask() differs.
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


class PromptedLLM:
    """Prompt rendering and validation shared by every real provider. Subclasses implement _ask()."""
    def _ask(self, system: str, user: str, max_tokens: int = 8000, json_only: bool = False) -> str:
        raise NotImplementedError

    def generate_problem(self, seed: dict, neighbours: list[dict], feedback: str | None) -> GeneratedProblem:
        user = render("problem.md", topic=seed["topic"], difficulty=seed["difficulty"],
                      neighbours=[{k: n[k] for k in ("title", "difficulty", "statement")} for n in neighbours],
                      schema=GeneratedProblem.model_json_schema(),
                      feedback=f"Your previous attempt failed verification: {feedback}\nFix it." if feedback else "")
        return GeneratedProblem.model_validate(extract_json(self._ask(render("system.md"), user, json_only=True)))

    def translate(self, reference_py: str, signature: dict, lang: str, starter: str) -> str:
        user = render("translate.md", lang=lang, reference=reference_py, starter=starter, signature=signature)
        return extract_code(self._ask(render("system.md"), user, 4000))


class AnthropicLLM(PromptedLLM):
    def __init__(self, model: str = CONFIG.llm_model):
        import anthropic
        self.client = anthropic.Anthropic()   # reads ANTHROPIC_API_KEY
        self.model = model

    def _ask(self, system: str, user: str, max_tokens: int = 8000, json_only: bool = False) -> str:
        msg = self.client.messages.create(model=self.model, max_tokens=max_tokens, system=system,
                                          messages=[{"role": "user", "content": user}])
        return "".join(b.text for b in msg.content if getattr(b, "type", "") == "text")


class OpenAILLM(PromptedLLM):
    """OpenAI Responses API. Problems are requested in JSON mode, so the reply is always one valid JSON object;
    pydantic then checks it against the schema. Works with small models such as gpt-4o-mini."""
    def __init__(self, model: str = CONFIG.llm_model, reasoning_effort: str = CONFIG.reasoning_effort, client=None):
        if client is None:
            from openai import OpenAI
            # Reads OPENAI_API_KEY. Retries 429 (rate limit) and 5xx with backoff.
            client = OpenAI(max_retries=5, timeout=300)
        self.client = client
        self.model = model
        self.reasoning_effort = reasoning_effort

    def _ask(self, system: str, user: str, max_tokens: int = 8000, json_only: bool = False) -> str:
        args = {"model": self.model, "instructions": system, "input": user, "max_output_tokens": max_tokens,
                "store": False}   # do not keep prompts and answers on OpenAI's side
        if json_only:
            args["text"] = {"format": {"type": "json_object"}}
        if self.reasoning_effort:   # only reasoning models (gpt-5.x, gpt-6.x) accept this; gpt-4o-mini does not
            args["reasoning"] = {"effort": self.reasoning_effort}
        res = self.client.responses.create(**args)
        if getattr(res, "status", "completed") == "incomplete":
            reason = getattr(getattr(res, "incomplete_details", None), "reason", "unknown")
            raise ValueError(f"model stopped early ({reason}); raise max_output_tokens or use a larger model")
        return res.output_text


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
    providers = {"openai": OpenAILLM, "anthropic": AnthropicLLM, "mock": MockLLM}
    if CONFIG.llm_provider not in providers:
        raise SystemExit(f"LLM_PROVIDER must be one of {', '.join(providers)}, not {CONFIG.llm_provider!r}")
    return providers[CONFIG.llm_provider]()