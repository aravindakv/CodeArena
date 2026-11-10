#!/usr/bin/env bash
# Runs the admin AI + RAG ingestion worker (reads .env). Stop with Ctrl+C.
set -euo pipefail
cd "$(dirname "$0")/../ingestion"
if command -v uv >/dev/null 2>&1; then
  uv sync -q && exec uv run codearena-ingest
fi
[[ -x .venv/bin/python ]] || { python3 -m venv .venv && .venv/bin/pip install -q -e '.[dev]'; }
exec .venv/bin/python -m codearena_ingest