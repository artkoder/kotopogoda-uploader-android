#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SCRIPT="$ROOT_DIR/scripts/prepare_models.py"

if ! command -v python3 >/dev/null 2>&1; then
  echo "python3 не найден в PATH" >&2
  exit 1
fi

exec python3 "$SCRIPT" "$@"
