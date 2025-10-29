#!/usr/bin/env python3
"""Сборка и конвертация моделей Zero-DCE++ и Restormer."""
from __future__ import annotations

import json
import math
import os
import shutil
import subprocess
import sys
import tarfile
import urllib.parse
import urllib.request
import zipfile
from hashlib import sha256
from pathlib import Path
from typing import Dict, List, Tuple

ROOT_DIR = Path(__file__).resolve().parents[1]
DEFAULT_WORK_DIR = ROOT_DIR / ".work" / "models"
MODEL_SOURCES_FILE = ROOT_DIR / "scripts" / "model_sources.lock.json"
DIST_DIR = ROOT_DIR / "dist"
MODELS_LOCK_PATH = ROOT_DIR / "models.lock.json"

# Окружение
WORK_DIR = Path(os.environ.get("WORK_DIR", DEFAULT_WORK_DIR))
SUMMARY_FILE = Path(os.environ.get("GITHUB_STEP_SUMMARY", WORK_DIR / "SUMMARY.md"))
RELEASE_TAG = os.environ.get("MODELS_RELEASE_TAG", "models-v1")

WORK_DOWNLOADS = WORK_DIR / "downloads"
WORK_CONVERTED = WORK_DIR / "converted"
WORK_STAGING = WORK_DIR / "staging"
WORK_TMP = WORK_DIR / "tmp"

USER_AGENT = "kotopogoda-model-prep/1.0"


def log(message: str) -> None:
    sys.stderr.write(f"[prepare_models] {message}\n")
    sys.stderr.flush()


def ensure_dirs() -> None:
    for path in (WORK_DIR, WORK_DOWNLOADS, WORK_CONVERTED, WORK_STAGING, WORK_TMP, DIST_DIR):
        path.mkdir(parents=True, exist_ok=True)


def load_sources() -> Dict[str, dict]:
    if not MODEL_SOURCES_FILE.exists():
        raise FileNotFoundError(f"Не найден {MODEL_SOURCES_FILE}")
    with MODEL_SOURCES_FILE.open("r", encoding="utf-8") as fp:
        return json.load(fp)


def build_request(url: str) -> urllib.request.Request:
    return urllib.request.Request(url, headers={"User-Agent": USER_AGENT})


def download_to_file(url: str, dest: Path) -> None:
    log(f"Скачиваем {url} → {dest}")
    dest.parent.mkdir(parents=True, exist_ok=True)
    with urllib.request.urlopen(build_request(url)) as response, dest.open("wb") as fout:
        shutil.copyfileobj(response, fout)


def download_entry(entry: dict, base_dir: Path) -> Path:
    entry_id = entry["id"]
    entry_type = entry.get("type", "file")
    target_root = base_dir / entry_id
    if target_root.exists():
        shutil.rmtree(target_root)
    target_root.mkdir(parents=True, exist_ok=True)

    if entry_type == "file":
        filename = entry.get("filename")
        if not filename:
            path_value = entry.get("path") or ""
            filename = Path(path_value).name or entry_id
        if "url" in entry:
            url = entry["url"]
        else:
            repo = entry["repo"]
            commit = entry["commit"]
            path_value = entry["path"]
            url = f"https://raw.githubusercontent.com/{repo}/{commit}/{path_value}"
        destination = target_root / filename
        download_to_file(url, destination)
        return destination

    if entry_type == "archive":
        repo = entry["repo"]
        commit = entry["commit"]
        url = entry.get("url") or f"https://codeload.github.com/{repo}/tar.gz/{commit}"
        archive_path = WORK_TMP / f"{entry_id}.tar.gz"
        if archive_path.exists():
            archive_path.unlink()
        download_to_file(url, archive_path)
        extract_dir = WORK_TMP / f"extract_{entry_id}"
        if extract_dir.exists():
            shutil.rmtree(extract_dir)
        extract_dir.mkdir(parents=True, exist_ok=True)
        with tarfile.open(archive_path, "r:gz") as tar:
            tar.extractall(extract_dir)
        members = [p for p in extract_dir.iterdir() if p.is_dir()]
        if not members:
            raise RuntimeError(f"Архив {url} не содержит директории")
        root_dir = members[0]
        shutil.move(str(root_dir), target_root)
        shutil.rmtree(extract_dir)
        archive_path.unlink(missing_ok=True)
        return target_root

    raise ValueError(f"Неизвестный тип источника: {entry_type}")


def sha256_of(path: Path) -> str:
    digest = sha256()
    with path.open("rb") as fp:
        for chunk in iter(lambda: fp.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def format_mib(size_bytes: int) -> float:
    return round(size_bytes / (1024 ** 2), 4)


def ensure_python_modules(modules: List[str]) -> None:
    missing = []
    for module in modules:
        try:
            __import__(module)
        except ImportError:
            missing.append(module)
    if missing:
        raise RuntimeError(
            "Требуются Python-модули: " + ", ".join(missing)
        )


def convert_zero_dce(model_cfg: dict, sources: Dict[str, Path], convert_dir: Path) -> Tuple[str, List[Dict[str, Path]]]:
    ensure_python_modules(["torch", "onnx", "onnx_tf", "tensorflow"])
    import importlib.util
    import torch
    from onnx_tf.backend import prepare
    import onnx
    import tensorflow as tf

    weights_path = sources["weights"]
    model_py = sources["model"]
    convert_dir.mkdir(parents=True, exist_ok=True)

    spec = importlib.util.spec_from_file_location("zerodce_model", model_py)
    module = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    spec.loader.exec_module(module)

    net = module.enhance_net_nopool(scale_factor=1)
    state = torch.load(weights_path, map_location="cpu")
    if isinstance(state, dict):
        for key in ("state_dict", "model", "net", "params"):
            if key in state:
                state = state[key]
                break
    if isinstance(state, dict):
        cleaned = {}
        for key, value in state.items():
            if key.startswith("module."):
                cleaned[key[len("module."):]] = value
            else:
                cleaned[key] = value
        state = cleaned
    net.load_state_dict(state, strict=False)
    net.eval()
    torch.set_grad_enabled(False)

    dummy_input = torch.rand(1, 3, 256, 256)
    onnx_path = convert_dir / "zerodcepp.onnx"
    torch.onnx.export(
        net,
        dummy_input,
        onnx_path,
        input_names=["input"],
        output_names=["enhanced", "curve"],
        dynamic_axes={
            "input": {2: "height", 3: "width"},
            "enhanced": {2: "height", 3: "width"},
            "curve": {2: "height", 3: "width"},
        },
        opset_version=12,
    )

    onnx_model = onnx.load(onnx_path)
    saved_model_dir = convert_dir / "zerodcepp_saved_model"
    if saved_model_dir.exists():
        shutil.rmtree(saved_model_dir)
    tf_rep = prepare(onnx_model)
    tf_rep.export_graph(str(saved_model_dir))

    converter = tf.lite.TFLiteConverter.from_saved_model(str(saved_model_dir))
    converter.optimizations = [tf.lite.Optimize.DEFAULT]
    converter.target_spec.supported_types = [tf.float16]
    converter.experimental_new_converter = True
    tflite_data = converter.convert()
    tflite_path = convert_dir / "zerodcepp_fp16.tflite"
    tflite_path.write_bytes(tflite_data)

    return "tflite", [
        {
            "path": tflite_path,
            "relative": "models/zerodcepp_fp16.tflite",
        }
    ]


def convert_restormer(model_cfg: dict, sources: Dict[str, Path], convert_dir: Path) -> Tuple[str, List[Dict[str, Path]]]:
    ensure_python_modules(["torch", "onnx", "onnx_tf", "tensorflow"])
    import torch
    from onnx_tf.backend import prepare
    import onnx
    import tensorflow as tf

    weights_path = sources["weights"]
    repo_root = sources["code"]

    convert_dir.mkdir(parents=True, exist_ok=True)

    repo_path = repo_root
    if not (repo_root / "basicsr").exists():
        for candidate in repo_root.iterdir():
            if candidate.is_dir() and (candidate / "basicsr").exists():
                repo_path = candidate
                break

    sys.path.insert(0, str(repo_path))
    try:
        from basicsr.models.archs.restormer_arch import Restormer  # type: ignore
    finally:
        sys.path.pop(0)

    torch.set_grad_enabled(False)
    model = Restormer()
    state = torch.load(weights_path, map_location="cpu")
    if isinstance(state, dict):
        for key in ("params", "state_dict", "model", "net"):
            if key in state:
                state = state[key]
                break
    if isinstance(state, dict):
        cleaned = {}
        for key, value in state.items():
            if key.startswith("module."):
                cleaned[key[len("module."):]] = value
            else:
                cleaned[key] = value
        state = cleaned
    model.load_state_dict(state, strict=False)
    model.eval()

    dummy = torch.rand(1, 3, 256, 256)
    onnx_path = convert_dir / "restormer.onnx"
    torch.onnx.export(
        model,
        dummy,
        onnx_path,
        input_names=["input"],
        output_names=["output"],
        dynamic_axes={
            "input": {2: "height", 3: "width"},
            "output": {2: "height", 3: "width"},
        },
        opset_version=17,
    )

    saved_model_dir = convert_dir / "restormer_saved_model"
    if saved_model_dir.exists():
        shutil.rmtree(saved_model_dir)

    try:
        onnx_model = onnx.load(onnx_path)
        tf_rep = prepare(onnx_model)
        tf_rep.export_graph(str(saved_model_dir))
        converter = tf.lite.TFLiteConverter.from_saved_model(str(saved_model_dir))
        converter.optimizations = [tf.lite.Optimize.DEFAULT]
        converter.target_spec.supported_types = [tf.float16]
        converter.experimental_new_converter = True
        tflite_data = converter.convert()
        tflite_path = convert_dir / "restormer_fp16.tflite"
        tflite_path.write_bytes(tflite_data)
        return "tflite", [
            {
                "path": tflite_path,
                "relative": "models/restormer_fp16.tflite",
            }
        ]
    except Exception as exc:
        log(f"Не удалось получить Restormer TFLite, fallback на NCNN: {exc}")
        onnx2ncnn = shutil.which("onnx2ncnn")
        if not onnx2ncnn:
            raise RuntimeError("Команда onnx2ncnn не найдена в PATH") from exc
        param_path = convert_dir / "restormer_fp16.param"
        bin_path = convert_dir / "restormer_fp16.bin"
        subprocess.run([onnx2ncnn, str(onnx_path), str(param_path), str(bin_path)], check=True)
        ncnnoptimize = shutil.which("ncnnoptimize")
        if ncnnoptimize:
            opt_param = convert_dir / "restormer_fp16.opt.param"
            opt_bin = convert_dir / "restormer_fp16.opt.bin"
            subprocess.run(
                [ncnnoptimize, str(param_path), str(bin_path), str(opt_param), str(opt_bin), "0"],
                check=True,
            )
            param_path.unlink()
            bin_path.unlink()
            opt_param.rename(param_path)
            opt_bin.rename(bin_path)
        return "ncnn", [
            {
                "path": param_path,
                "relative": "models/restormer_fp16.param",
            },
            {
                "path": bin_path,
                "relative": "models/restormer_fp16.bin",
            },
        ]


def process_model(key: str, cfg: dict) -> dict:
    log(f"Готовим {cfg['name']}")
    downloads_dir = WORK_DOWNLOADS / key
    if downloads_dir.exists():
        shutil.rmtree(downloads_dir)
    downloads_dir.mkdir(parents=True, exist_ok=True)

    sources: Dict[str, Path] = {}
    for entry in cfg.get("sources", []):
        result = download_entry(entry, downloads_dir)
        sources[entry["id"]] = result

    convert_dir = WORK_CONVERTED / key
    if convert_dir.exists():
        shutil.rmtree(convert_dir)
    convert_dir.mkdir(parents=True, exist_ok=True)

    if key == "zerodcepp_fp16":
        backend, files = convert_zero_dce(cfg, sources, convert_dir)
    elif key == "restormer_fp16":
        backend, files = convert_restormer(cfg, sources, convert_dir)
    else:
        raise RuntimeError(f"Неизвестная модель: {key}")

    staging_dir = WORK_STAGING / f"{cfg['artifact']}_{cfg['version']}"
    if staging_dir.exists():
        shutil.rmtree(staging_dir)
    staging_models_dir = staging_dir / "models"
    staging_models_dir.mkdir(parents=True, exist_ok=True)

    file_entries = []
    for descriptor in files:
        src = descriptor["path"]
        relative = descriptor["relative"]
        dest = staging_dir / relative
        dest.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(src, dest)
        file_entries.append(
            {
                "path": relative,
                "sha256": sha256_of(dest),
                "min_mb": format_mib(dest.stat().st_size),
            }
        )

    artifact_name = f"{cfg['artifact']}_{cfg['version']}.zip"
    zip_path = DIST_DIR / artifact_name
    if zip_path.exists():
        zip_path.unlink()
    with zipfile.ZipFile(zip_path, "w", compression=zipfile.ZIP_DEFLATED) as zf:
        for entry in file_entries:
            absolute = staging_dir / entry["path"]
            zf.write(absolute, arcname=entry["path"])

    artifact_sha = sha256_of(zip_path)
    artifact_size = format_mib(zip_path.stat().st_size)

    return {
        "key": key,
        "display": cfg["name"],
        "artifact_name": artifact_name,
        "artifact_path": zip_path,
        "artifact_sha": artifact_sha,
        "artifact_size": artifact_size,
        "backend": backend,
        "files": file_entries,
    }


def write_sha_sums(results: List[dict]) -> None:
    sha_path = DIST_DIR / "SHA256SUMS.txt"
    with sha_path.open("w", encoding="utf-8") as fp:
        for result in results:
            fp.write(f"{result['artifact_sha']}  {result['artifact_name']}\n")
    log(f"SHA256SUMS.txt обновлён: {sha_path}")


def write_summary(results: List[dict]) -> None:
    SUMMARY_FILE.parent.mkdir(parents=True, exist_ok=True)
    lines = [
        "| Модель | Backend | Размер (MiB) | SHA-256 |",
        "| --- | --- | --- | --- |",
    ]
    for result in results:
        lines.append(
            f"| {result['display']} | {result['backend']} | {result['artifact_size']:.2f} | {result['artifact_sha']} |"
        )
    lines.append("")
    lines.append("Файл `SHA256SUMS.txt` записан в `dist/`.")
    SUMMARY_FILE.write_text("\n".join(lines), encoding="utf-8")
    log(f"Summary записан в {SUMMARY_FILE}")


def write_models_lock(results: List[dict]) -> None:
    repository = ""
    if MODELS_LOCK_PATH.exists():
        try:
            with MODELS_LOCK_PATH.open("r", encoding="utf-8") as fp:
                repository = json.load(fp).get("repository", "")
        except Exception:
            repository = ""
    if not repository:
        repository = os.environ.get("GITHUB_REPOSITORY", "")

    models_payload = {}
    for result in results:
        models_payload[result["key"]] = {
            "release": RELEASE_TAG,
            "asset": result["artifact_name"],
            "sha256": result["artifact_sha"],
            "backend": result["backend"],
            "min_mb": result["artifact_size"],
            "files": result["files"],
        }
    payload = {
        "repository": repository,
        "models": models_payload,
    }
    MODELS_LOCK_PATH.write_text(json.dumps(payload, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    log(f"models.lock.json обновлён")


def main() -> None:
    ensure_dirs()
    sources = load_sources()
    results: List[dict] = []
    for key, cfg in sources.items():
        results.append(process_model(key, cfg))
    write_sha_sums(results)
    write_summary(results)
    write_models_lock(results)


if __name__ == "__main__":
    try:
        main()
    except Exception as exc:
        log(f"Ошибка: {exc}")
        sys.exit(1)
