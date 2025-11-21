#!/usr/bin/env python3
"""Генератор models.lock.json в runtime-формате для приложения."""
from __future__ import annotations

import hashlib
import json
import os
import pathlib
import sys
from typing import Dict, List

ROOT_DIR = pathlib.Path(__file__).resolve().parents[1]


def _resolve_path(raw: str | None, default: pathlib.Path) -> pathlib.Path:
    if not raw:
        return default
    candidate = pathlib.Path(raw).expanduser()
    if not candidate.is_absolute():
        candidate = ROOT_DIR / candidate
    return candidate


def sha256(path: pathlib.Path) -> str:
    """Вычисляет SHA256 хеш файла."""
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


DIST = _resolve_path(os.environ.get("MODELS_DIST"), ROOT_DIR / "dist")
MODELS_ROOT = DIST / "models"
MODELS_LOCK_PATH = _resolve_path(
    os.environ.get("MODELS_LOCK_PATH") or os.environ.get("MODELS_LOCK_JSON"),
    ROOT_DIR / "models.lock.json",
)
CONTRACT = os.environ.get("MODELS_CONTRACT_VERSION", "v1.4.1")


def load_runtime_entries() -> List[Dict[str, object]]:
    if not MODELS_LOCK_PATH.exists():
        print(f"[ERROR] missing {MODELS_LOCK_PATH}", file=sys.stderr)
        sys.exit(2)

    payload = json.loads(MODELS_LOCK_PATH.read_text(encoding="utf-8"))
    models: Dict[str, dict] = payload.get("models", {})
    entries: List[Dict[str, object]] = []

    for model_id in sorted(models.keys()):
        cfg = models[model_id]
        if not cfg.get("enabled", True):
            continue

        backend_value = cfg.get("backend", "ncnn")
        normalized_backend = str(backend_value).lower()
        if normalized_backend != "ncnn":
            continue

        unzipped_root = pathlib.Path(cfg.get("unzipped") or "models")
        files_cfg = cfg.get("files") or []
        if not files_cfg:
            continue

        files: List[Dict[str, str]] = []
        for descriptor in files_cfg:
            rel_name = descriptor.get("path")
            if not rel_name:
                continue
            rel_path = unzipped_root / rel_name
            if rel_path.is_absolute():
                print(
                    f"[ERROR] absolute paths are not supported in models.lock: {rel_path}",
                    file=sys.stderr,
                )
                sys.exit(2)
            if ".." in rel_path.parts:
                print(
                    f"[ERROR] path traversal is not allowed in models.lock: {rel_path}",
                    file=sys.stderr,
                )
                sys.exit(2)
            disk_path = DIST / rel_path
            if not disk_path.exists():
                print(f"[ERROR] missing model file: {disk_path}", file=sys.stderr)
                sys.exit(2)
            files.append(
                {
                    "path": rel_path.as_posix(),
                    "sha256": sha256(disk_path),
                }
            )

        if files:
            entries.append({
                "id": model_id,
                "backend": normalized_backend,
                "files": files,
            })

    return entries


def main() -> None:
    if not MODELS_ROOT.exists():
        print(f"[ERROR] missing {MODELS_ROOT}", file=sys.stderr)
        sys.exit(2)

    model_entries = load_runtime_entries()

    sums_path = DIST / "SHA256SUMS.txt"
    jq_sum = sha256(sums_path) if sums_path.exists() else "0" * 64
    model_entries.append({
        "id": "metadata",
        "backend": "METADATA",
        "jq_sha256sum": jq_sum,
    })

    runtime_lock = {
        "api_contract_version": CONTRACT,
        "entries": model_entries,
    }

    out_path = DIST / "models.lock.json"
    out_path.write_text(
        json.dumps(runtime_lock, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )
    print(f"[OK] wrote runtime lock: {out_path}")


if __name__ == "__main__":
    main()
