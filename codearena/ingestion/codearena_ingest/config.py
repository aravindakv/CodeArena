import os
from dataclasses import dataclass, field
from pathlib import Path

DOTENV_KEYS: set[str] = set()   # names whose value came from .env (not from the shell environment)
DOTENV_PATH: Path | None = None


def _load_dotenv():
    """Read the repo's .env (KEY=VALUE lines) without overriding real environment variables.
    Surrounding quotes and a trailing "# comment" after a space are removed, as in docker compose."""
    global DOTENV_PATH
    for candidate in (Path.cwd() / ".env", Path(__file__).resolve().parents[2] / ".env"):
        if candidate.is_file():
            DOTENV_PATH = candidate
            for line in candidate.read_text().splitlines():
                line = line.strip()
                if line.startswith("export "):
                    line = line[7:].strip()
                if line and not line.startswith("#") and "=" in line:
                    k, v = (x.strip() for x in line.split("=", 1))
                    if len(v) >= 2 and v[0] == v[-1] and v[0] in "'\"":
                        v = v[1:-1]
                    elif " #" in v:
                        v = v.split(" #", 1)[0].strip()
                    if k not in os.environ:
                        os.environ[k] = v
                        DOTENV_KEYS.add(k)
            return


def key_source(name: str) -> str:
    """Human-readable origin of a secret, without revealing it: where it came from and its last 4 characters."""
    value = os.environ.get(name, "")
    if not value:
        return f"{name} is not set (add it to {DOTENV_PATH or '.env'})"
    where = f".env ({DOTENV_PATH})" if name in DOTENV_KEYS else "your shell environment, which overrides .env"
    return f"{name} ending in ...{value[-4:]} from {where}"


_load_dotenv()


# Model used when LLM_MODEL is empty. Change them in .env, not here, when newer models come out.
DEFAULT_MODELS = {"openai": "gpt-4o-mini", "anthropic": "claude-sonnet-5-5"}


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

    llm_provider: str = os.environ.get("LLM_PROVIDER", "mock")          # "openai", "anthropic" or "mock"
    llm_model: str = os.environ.get("LLM_MODEL") or DEFAULT_MODELS.get(os.environ.get("LLM_PROVIDER", "mock"), "")
    reasoning_effort: str = os.environ.get("REASONING_EFFORT", "")  # only for OpenAI reasoning models; empty = omit
    embeddings: str = os.environ.get("EMBEDDINGS", "hash")              # "openai", "fastembed" or "hash"
    openai_embedding_model: str = os.environ.get("OPENAI_EMBEDDING_MODEL", "text-embedding-3-small")
    judge_runtime: str = os.environ.get("JUDGE_RUNTIME", "runsc")
    work_root: str = os.environ.get("INGEST_WORK_ROOT", "/tmp/codearena-ingest")
    verify_langs: list[str] = field(default_factory=lambda: [
        x.strip() for x in os.environ.get("VERIFY_LANGS", "python").split(",") if x.strip()])
    duplicate_threshold: float = float(os.environ.get("DUPLICATE_THRESHOLD", "0.92"))
    max_attempts: int = int(os.environ.get("GENERATION_ATTEMPTS", "2"))


CONFIG = Config()