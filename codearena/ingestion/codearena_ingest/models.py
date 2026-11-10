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