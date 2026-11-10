#!/usr/bin/env bash
# Builds every runner image. Run from anywhere: sandbox-images/build-all.sh [lang...]
set -euo pipefail
cd "$(dirname "$0")"
langs=("$@"); [[ ${#langs[@]} -eq 0 ]] && langs=(python javascript java cpp go)
for l in "${langs[@]}"; do
  echo "==> codearena/runner-$l:1"
  docker build -t "codearena/runner-$l:1" -f "$l/Dockerfile" .
done