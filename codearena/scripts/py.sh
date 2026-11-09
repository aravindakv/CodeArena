#!/usr/bin/env bash
# Run a helper Python script with its dependencies, without touching your system Python.
#   scripts/py.sh scripts/bootstrap.py
# Uses `uv` if installed (recommended: curl -LsSf https://astral.sh/uv/install.sh | sh),
# otherwise a virtualenv in .local/venv.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEPS=(boto3 requests)

# Load .env if present so scripts see the same endpoints as the services.
if [[ -f "$ROOT/.env" ]]; then set -a; source "$ROOT/.env"; set +a; fi

if command -v uv >/dev/null 2>&1; then
  args=(); for d in "${DEPS[@]}"; do args+=(--with "$d"); done
  exec uv run --no-project --python 3.13 "${args[@]}" python "$@"
fi

VENV="$ROOT/.local/venv"
if [[ ! -x "$VENV/bin/python" ]]; then
  mkdir -p "$ROOT/.local"
  python3 -m venv "$VENV"
  "$VENV/bin/pip" install -q "${DEPS[@]}"
fi
exec "$VENV/bin/python" "$@"