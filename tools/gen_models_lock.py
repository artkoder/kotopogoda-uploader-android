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


def _as_float(value: object | None) -> float | None:
    if value is None or isinstance(value, bool):
        return None
    if isinstance(value, (int, float)):
        return float(value)
    if isinstance(value, str):
        stripped = value.strip()
        if not stripped:
            return None
        try:
            return float(stripped)
        except ValueError:
            return None
    return None


def _to_positive_int(value: object | None) -> int | None:
    number = _as_float(value)
    if number is None:
        return None
    candidate = int(number)
    if candidate <= 0:
        return None
    return candidate


def _megabytes_to_bytes(value: object | None) -> int:
    number = _as_float(value)
    if number is None or number <= 0:
        return 0
    return int(number * 1024 * 1024)


def _normalize_precision(value: object | None) -> str | None:
    if value is None:
        return None
    text = str(value).strip().lower()
    return text or None


def _backend_metadata(model_cfg: Dict[str, object], backend: str) -> Dict[str, object]:
    metadata = model_cfg.get("metadata")
    if not isinstance(metadata, dict):
        return {}
    candidates = (backend, backend.lower(), backend.upper())
    for key in candidates:
        payload = metadata.get(key)
        if isinstance(payload, dict):
            return payload
    return {}


def _resolve_min_bytes(file_cfg: dict, fallback_bytes: int) -> int:
    override_bytes = _to_positive_int(file_cfg.get("min_bytes"))
    if override_bytes is not None:
        return override_bytes
    descriptor_bytes = _megabytes_to_bytes(file_cfg.get("min_mb"))
    if descriptor_bytes > 0:
        return descriptor_bytes
    return fallback_bytes


def _should_include_in_runtime(model_cfg: Dict[str, object]) -> bool:
    runtime_cfg = model_cfg.get("runtime")
    if isinstance(runtime_cfg, bool):
        return runtime_cfg
    if isinstance(runtime_cfg, dict):
        include_value = runtime_cfg.get("include")
        exclude_value = runtime_cfg.get("exclude")
        if isinstance(exclude_value, bool) and exclude_value:
            return False
        if isinstance(include_value, bool):
            return include_value
    return True


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
        if not _should_include_in_runtime(cfg):
            continue

        backend_value = cfg.get("backend", "ncnn")
        normalized_backend = str(backend_value).lower()
        if normalized_backend != "ncnn":
            continue

        backend_meta = _backend_metadata(cfg, normalized_backend)
        unzipped_root = pathlib.Path(cfg.get("unzipped") or "models")
        common_min_bytes = _megabytes_to_bytes(cfg.get("min_mb"))
        files_cfg = cfg.get("files") or []
        if not files_cfg:
            continue

        files: List[Dict[str, object]] = []
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
            min_bytes = _resolve_min_bytes(descriptor, common_min_bytes)
            file_entry: Dict[str, object] = {
                "path": rel_path.as_posix(),
                "sha256": sha256(disk_path),
            }
            if min_bytes > 0:
                file_entry["min_bytes"] = min_bytes
            files.append(file_entry)

        if not files:
            continue

        entry: Dict[str, object] = {
            "id": model_id,
            "backend": normalized_backend,
            "files": files,
        }

        precision = _normalize_precision(
            cfg.get("precision") or backend_meta.get("precision")
        )
        if precision:
            entry["precision"] = precision

        tile_size = _to_positive_int(backend_meta.get("tile_size"))
        if tile_size is None:
            tile_size = _to_positive_int(cfg.get("tile_size"))
        if tile_size is not None:
            entry["tile_size"] = tile_size

        entries.append(entry)

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
