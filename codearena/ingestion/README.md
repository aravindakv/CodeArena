# CodeArena ingestion worker (admin-only AI + RAG)

Started from the admin console (AI ingestion), which queues one SQS message per seed. This worker turns each seed
into a verified, original problem and leaves it in the review queue. See guide chapter 14 (14-ai-rag-ingestion-openai.md for OpenAI).

```bash
cd ingestion
uv sync --extra dev                       # or: python3 -m venv .venv && .venv/bin/pip install -e '.[dev]'
uv run pytest -q                          # logic tests, no Docker/AWS needed
uv run codearena-ingest                   # the worker (needs scripts/up.sh, problem-service, runner images)
```

Python 3.11+ (3.13 recommended). Settings come from the repo's `.env`: `LLM_PROVIDER=mock|openai|anthropic`, `OPENAI_API_KEY`, `ANTHROPIC_API_KEY`, `LLM_MODEL` (empty = gpt-4o-mini
for OpenAI), `REASONING_EFFORT`, `EMBEDDINGS=hash|fastembed|openai` (fastembed needs `uv sync --extra embeddings`), `VERIFY_LANGS=python,java,cpp,javascript,go`.