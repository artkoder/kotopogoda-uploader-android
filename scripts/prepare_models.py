#!/usr/bin/env python3
"""Сборка и конвертация моделей Zero-DCE++ и Restormer."""
from __future__ import annotations

import json
import os
import platform
import shutil
import subprocess
import sys
import tarfile
import urllib.parse
import urllib.request
import zipfile
from hashlib import sha256
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple


try:
    from importlib import metadata as importlib_metadata
except ImportError:  # pragma: no cover - поддержка старых Python
    import importlib_metadata as importlib_metadata  # type: ignore


_PIP_BOOTSTRAPPED = False


def _ensure_pip_bootstrapped() -> None:
    """Гарантирует доступность внутренних зависимостей pip."""

    global _PIP_BOOTSTRAPPED
    if _PIP_BOOTSTRAPPED:
        return

    try:
        import pip._vendor.pkg_resources  # type: ignore  # noqa: F401
    except ModuleNotFoundError:
        log("pkg_resources отсутствует; пытаемся выполнить ensurepip")
        try:
            subprocess.run(
                [sys.executable, "-m", "ensurepip", "--upgrade"],
                check=True,
            )
        except (subprocess.CalledProcessError, FileNotFoundError) as ensure_error:
            log(
                "ensurepip завершился с ошибкой; pip может остаться "
                f"недоступным: {ensure_error}"
            )
        else:
            log("ensurepip успешно восстановил окружение pip")
    _PIP_BOOTSTRAPPED = True


def pip_install(requirement: str) -> bool:
    """Устанавливает указанный Python-пакет через pip."""

    _ensure_pip_bootstrapped()
    log(f"Устанавливаем пакет {requirement}")
    upgrade_cmd = [
        sys.executable,
        "-m",
        "pip",
        "install",
        "--upgrade",
        "--disable-pip-version-check",
        requirement,
    ]
    base_cmd = [
        sys.executable,
        "-m",
        "pip",
        "install",
        "--disable-pip-version-check",
        requirement,
    ]
    try:
        subprocess.run(upgrade_cmd, check=True)
        return True
    except subprocess.CalledProcessError as upgrade_error:
        log("Повторная попытка установки без --upgrade")
        try:
            subprocess.run(base_cmd, check=True)
            return True
        except subprocess.CalledProcessError as base_error:
            log(
                "Не удалось установить пакет "
                f"{requirement}: {base_error}; пропускаем"
            )
            if upgrade_error.stderr:
                log(upgrade_error.stderr.decode(errors="ignore"))
            if base_error.stderr:
                log(base_error.stderr.decode(errors="ignore"))
            return False

ROOT_DIR = Path(__file__).resolve().parents[1]
DEFAULT_WORK_DIR = ROOT_DIR / ".work" / "models"
MODEL_SOURCES_FILE = ROOT_DIR / "scripts" / "model_sources.lock.json"

_DIST_ENV = os.environ.get("MODELS_DIST")
if _DIST_ENV:
    _dist_candidate = Path(_DIST_ENV).expanduser()
    if not _dist_candidate.is_absolute():
        _dist_candidate = ROOT_DIR / _dist_candidate
else:
    _dist_candidate = ROOT_DIR / "dist"
DIST_DIR = _dist_candidate
DIST_MODELS_DIR = DIST_DIR / "models"
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
    if DIST_MODELS_DIR.exists():
        shutil.rmtree(DIST_MODELS_DIR)
    DIST_MODELS_DIR.mkdir(parents=True, exist_ok=True)


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
        for item in root_dir.iterdir():
            shutil.move(str(item), str(target_root / item.name))
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




def parse_bool(value: object, default: bool = True) -> bool:
    if isinstance(value, bool):
        return value
    if isinstance(value, (int, float)):
        return value != 0
    if isinstance(value, str):
        normalized = value.strip().lower()
        if normalized in {"true", "1", "yes", "on"}:
            return True
        if normalized in {"false", "0", "no", "off"}:
            return False
    return default


def coerce_int(value: object, default: int) -> int:
    if isinstance(value, int):
        return value
    if isinstance(value, float):
        return int(value)
    if isinstance(value, str):
        stripped = value.strip()
        if not stripped:
            return default
        try:
            return int(stripped)
        except ValueError:
            return default
    return default


def collect_onnx_operator_types(onnx_path: Path) -> List[str]:
    try:
        import onnx

        model = onnx.load(str(onnx_path))
    except Exception as exc:
        log(f"Не удалось загрузить ONNX-модель {onnx_path}: {exc}")
        return []

    return sorted({node.op_type for node in model.graph.node})


MODULE_INSTALL_MAP = {
    "torch": ["torch", "torchvision"],
    "onnx": ["onnx==1.16.*"],
    "onnxsim": ["onnxsim==0.4.*"],
    "onnxruntime": ["onnxruntime==1.16.*"],
    "einops": ["einops"],
    "cv2": ["opencv-python-headless"],
    "scipy": ["scipy"],
    "skimage": ["scikit-image"],
    "tqdm": ["tqdm"],
    "yaml": ["pyyaml"],
}


def _collect_missing_modules(modules: List[str]) -> Tuple[List[str], Dict[str, ImportError]]:
    """Возвращает список отсутствующих модулей с учётом вложенных зависимостей."""

    seen: set[str] = set()
    queue: List[str] = []
    for module in modules:
        if module not in seen:
            seen.add(module)
            queue.append(module)

    missing: Dict[str, ImportError] = {}

    while queue:
        module = queue.pop(0)
        try:
            __import__(module)
        except ImportError as exc:
            if module in missing:
                continue
            missing[module] = exc
            if isinstance(exc, ModuleNotFoundError):
                dependency = getattr(exc, "name", None)
                if dependency and dependency not in seen:
                    seen.add(dependency)
                    queue.append(dependency)

    return list(missing.keys()), missing


def ensure_python_modules(modules: List[str]) -> None:
    requested_modules: List[str] = list(modules)
    attempted_requirements: set[str] = set()

    while True:
        missing, missing_errors = _collect_missing_modules(requested_modules)
        if not missing:
            return

        progress = False
        for module in missing:
            requirements = MODULE_INSTALL_MAP.get(module, [])
            if callable(requirements):  # type: ignore[callable-impl]
                requirements = requirements()
            if not requirements:
                continue
            for requirement in requirements:
                if requirement in attempted_requirements:
                    continue
                attempted_requirements.add(requirement)
                if pip_install(requirement):
                    progress = True

        for module in missing:
            if module not in requested_modules:
                requested_modules.append(module)

        if not progress:
            details: List[str] = []
            for module in missing:
                exc = missing_errors.get(module)
                if exc is None:
                    details.append(module)
                    continue
                if isinstance(exc, ModuleNotFoundError) and getattr(exc, "name", None) != module:
                    details.append(f"{module} (зависимость {exc.name} недоступна)")
                else:
                    details.append(f"{module} ({exc})")
            raise RuntimeError(
                "Требуются Python-модули: " + ", ".join(details)
            )


def convert_zero_dce(
    model_cfg: dict, sources: Dict[str, Path], convert_dir: Path
) -> Tuple[str, List[Dict[str, Any]], Dict[str, object]]:
    ensure_python_modules(
        [
            "torch",
            "onnx",
            "onnxsim",
        ]
    )

    import importlib.util

    import torch
    import onnx
    from onnxsim import simplify
    
    # Логирование версий ключевых пакетов
    log(f"PyTorch: {torch.__version__}, ONNX: {onnx.__version__}")

    weights_path = sources["weights"]
    model_py = sources["model"]
    convert_dir.mkdir(parents=True, exist_ok=True)

    spec = importlib.util.spec_from_file_location("zerodce_model", model_py)
    module = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    spec.loader.exec_module(module)

    base_model = module.enhance_net_nopool(scale_factor=1)
    
    # Проверка существования файла весов
    if not weights_path.exists():
        raise FileNotFoundError(f"Веса не найдены: {weights_path}")
    
    log(f"Загружаем веса из {weights_path}")
    state = torch.load(weights_path, map_location="cpu")
    if isinstance(state, dict):
        for key in ("state_dict", "model", "net", "params"):
            if key in state:
                log(f"Извлекаем state_dict из ключа '{key}'")
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
    
    # Проверка, что state_dict не пустой
    if not isinstance(state, dict) or not state:
        raise RuntimeError(f"State dict пустой или некорректный, тип: {type(state)}")
    
    log(f"State dict содержит {len(state)} ключей")
    
    # Загрузка весов в модель
    missing_keys, unexpected_keys = base_model.load_state_dict(state, strict=False)
    if missing_keys:
        log(f"Предупреждение: отсутствующие ключи при загрузке: {missing_keys[:5]}")
    if unexpected_keys:
        log(f"Предупреждение: неожиданные ключи при загрузке: {unexpected_keys[:5]}")
    
    # Проверка количества параметров
    total_params = sum(p.numel() for p in base_model.parameters())
    trainable_params = sum(p.numel() for p in base_model.parameters() if p.requires_grad)
    log(f"Веса загружены: {total_params:,} параметров (тренируемых: {trainable_params:,})")
    
    if total_params == 0:
        raise RuntimeError("Модель не содержит параметров после загрузки весов")
    
    base_model.eval()
    torch.set_grad_enabled(False)

    class _ZeroDCEWrapper(torch.nn.Module):
        def __init__(self, inner: torch.nn.Module) -> None:
            super().__init__()
            self.inner = inner

        def forward(self, x):  # type: ignore[override]
            result = self.inner(x)
            if isinstance(result, (tuple, list)):
                return result[0]
            return result

    model = _ZeroDCEWrapper(base_model)
    model.eval()

    dummy_input = torch.rand(1, 3, 512, 512)
    onnx_path = convert_dir / "zerodcepp.onnx"
    log(f"Экспортируем PyTorch → ONNX (opset 18, legacy mode): {onnx_path}")
    torch.onnx.export(
        model,
        dummy_input,
        onnx_path,
        input_names=["input"],
        output_names=["output"],
        dynamic_axes={
            "input": {0: "batch", 2: "height", 3: "width"},
            "output": {0: "batch", 2: "height", 3: "width"},
        },
        opset_version=18,
        dynamo=False,
    )
    
    onnx_size = onnx_path.stat().st_size
    onnx_size_mb = onnx_size / (1024 * 1024)
    log(f"ONNX модель создана: {format_mib(onnx_size)} MiB")
    
    # Zero-DCE++ — очень легковесная модель (10,561 параметр),
    # поэтому ONNX файл будет ~80 KB, что нормально
    MIN_EXPECTED_SIZE_KB = 50
    onnx_size_kb = onnx_size / 1024
    if onnx_size_kb < MIN_EXPECTED_SIZE_KB:
        raise RuntimeError(
            f"ONNX файл слишком маленький ({onnx_size} байт = {onnx_size_kb:.1f} KB), "
            f"ожидается минимум {MIN_EXPECTED_SIZE_KB} KB. "
            "Возможно, экспорт провален."
        )

    log(f"✅ ONNX размер валиден: {onnx_size_kb:.1f} KB")

    simplified_path = convert_dir / "zerodcepp_simplified.onnx"
    try:
        log("Упрощаем ONNX граф с помощью onnxsim...")
        simplified_model, check = simplify(str(onnx_path), dynamic_input_shape=True)
    except Exception as exc:
        log(f"Не удалось упростить ONNX: {exc}; используется исходная модель")
        simplified_path = onnx_path
    else:
        if check:
            onnx.save(simplified_model, simplified_path.as_posix())
            simplified_size = simplified_path.stat().st_size
            log(f"ONNX упрощен: {format_mib(simplified_size)} MiB")
        else:
            log("onnxsim не подтвердил корректность модели; используется исходный ONNX")
            simplified_path = onnx_path

    log("Конвертируем ONNX → NCNN...")
    onnx2ncnn = shutil.which("onnx2ncnn")
    if not onnx2ncnn:
        raise RuntimeError("Команда onnx2ncnn не найдена в PATH")

    param_path = convert_dir / "zerodcepp_fp16.param"
    bin_path = convert_dir / "zerodcepp_fp16.bin"
    
    subprocess.run(
        [onnx2ncnn, str(simplified_path), str(param_path), str(bin_path)],
        check=True,
    )
    
    if not param_path.exists() or not bin_path.exists():
        raise RuntimeError("onnx2ncnn не создал .param/.bin файлы")
    
    bin_size = bin_path.stat().st_size
    log(f"NCNN модель создана: .param + .bin ({format_mib(bin_size)} MiB)")
    
    # Zero-DCE++ — очень легковесная модель (10,561 параметр),
    # поэтому NCNN .bin будет ~40 KB, что нормально
    MIN_EXPECTED_BIN_SIZE_KB = 30  # минимум 30 KB
    bin_size_kb = bin_size / 1024
    
    if bin_size_kb < MIN_EXPECTED_BIN_SIZE_KB:
        raise RuntimeError(
            f"NCNN .bin файл слишком маленький ({bin_size} байт = {bin_size_kb:.1f} KB), "
            f"ожидается минимум {MIN_EXPECTED_BIN_SIZE_KB} KB. "
            "Возможно, конвертация провалена."
        )
    
    log(f"✅ NCNN .bin размер валиден: {bin_size_kb:.1f} KB")

    # Zero-DCE++ — очень маленькая модель (41 KB), оптимизация не критична
    # и вызывает SIGFPE ошибку в ncnnoptimize
    log("Пропускаем ncnnoptimize для маленькой модели Zero-DCE++")
    log("✅ NCNN модель готова (без оптимизации)")

    metadata: Dict[str, object] = {
        "ncnn": {
            "param_size_bytes": param_path.stat().st_size,
            "bin_size_bytes": bin_size,
            "bin_size_mib": format_mib(bin_size),
            "sha256_param": sha256_of(param_path),
            "sha256_bin": sha256_of(bin_path),
        },
        "onnx": {
            "path": str(simplified_path.name),
            "sha256": sha256_of(simplified_path),
            "size_mib": format_mib(simplified_path.stat().st_size),
            "operators": collect_onnx_operator_types(simplified_path),
        },
    }

    files = [
        {
            "path": param_path,
            "relative": Path("models/zerodcepp_fp16.param"),
            "include": True,
            "label": "param",
        },
        {
            "path": bin_path,
            "relative": Path("models/zerodcepp_fp16.bin"),
            "include": True,
            "label": "bin",
        },
        {
            "path": simplified_path,
            "relative": Path("zerodcepp_simplified.onnx"),
            "include": False,
            "label": "onnx",
        },
    ]

    return "ncnn", files, metadata


def convert_restormer(
    model_cfg: dict, sources: Dict[str, Path], convert_dir: Path
) -> Tuple[str, List[Dict[str, Any]], Dict[str, object]]:
    ensure_python_modules([
        "torch",
        "onnx",
        "onnxsim",
        "einops",
    ])
    import torch
    import onnx
    from onnxsim import simplify

    precision_raw = model_cfg.get("precision", "fp16")
    precision = str(precision_raw).strip().lower() if precision_raw is not None else "fp16"
    if not precision:
        precision = "fp16"
    if precision not in {"fp16", "fp32"}:
        raise ValueError(f"Restormer поддерживает только fp16/fp32, получено: {precision}")
    artifact_basename = str(model_cfg.get("artifact") or f"restormer_{precision}")
    log(f"Готовим Restormer ({precision.upper()}) для артефакта {artifact_basename}")

    tile_size_env = os.environ.get("RESTORMER_TILE_SIZE") or os.environ.get("TILE_SIZE")
    tile_size = coerce_int(model_cfg.get("tile_size"), 0)
    if tile_size <= 0:
        tile_size = coerce_int(tile_size_env, 0)
    if tile_size <= 0:
        tile_size = 384

    # Логирование версий ключевых пакетов
    log(f"PyTorch: {torch.__version__}, ONNX: {onnx.__version__}")

    weights_path = sources["weights"]
    repo_root = sources["code"]

    convert_dir.mkdir(parents=True, exist_ok=True)

    restormer_import_root: Optional[Path] = None
    direct_candidate = repo_root / "basicsr"
    if direct_candidate.is_dir():
        restormer_import_root = repo_root
    else:
        for candidate in sorted(repo_root.iterdir()):
            if candidate.is_dir() and (candidate / "basicsr").is_dir():
                restormer_import_root = candidate
                break
    if restormer_import_root is None:
        raise RuntimeError(
            "Не удалось найти директорию 'basicsr' в исходниках Restormer."
        )

    sys.path.insert(0, str(restormer_import_root))
    try:
        from basicsr.models.archs.restormer_arch import Restormer  # type: ignore
    finally:
        sys.path.pop(0)

    torch.set_grad_enabled(False)
    model = Restormer(
        bias=True,
    )
    
    if not weights_path.exists():
        raise FileNotFoundError(f"Веса не найдены: {weights_path}")
    
    log(f"Загружаем веса из {weights_path}")
    
    checkpoint = torch.load(weights_path, map_location="cpu")
    
    state_dict = None
    used_key = None
    
    if isinstance(checkpoint, dict):
        for key in ("params_ema", "params", "state_dict", "model", "net"):
            if key in checkpoint:
                state_dict = checkpoint[key]
                used_key = key
                log(f"Используем '{key}' из checkpoint")
                break
        
        if state_dict is None:
            state_dict = checkpoint
            used_key = "checkpoint (прямой)"
            log("Используем checkpoint напрямую")
    else:
        state_dict = checkpoint
        used_key = "checkpoint (не dict)"
        log("Используем checkpoint напрямую (не dict)")
    
    if isinstance(state_dict, dict):
        cleaned = {}
        for key, value in state_dict.items():
            if key.startswith("module."):
                cleaned[key[len("module."):]] = value
            else:
                cleaned[key] = value
        state_dict = cleaned
    
    missing_keys, unexpected_keys = model.load_state_dict(state_dict, strict=False)
    
    if missing_keys:
        log(f"⚠️  Missing keys (игнорируем): {len(missing_keys)} шт.")
        for key in missing_keys[:5]:
            log(f"  - {key}")
        if len(missing_keys) > 5:
            log(f"  ... и ещё {len(missing_keys) - 5}")
    
    if unexpected_keys:
        log(f"⚠️  Unexpected keys (игнорируем): {len(unexpected_keys)} шт.")
        for key in unexpected_keys[:5]:
            log(f"  - {key}")
        if len(unexpected_keys) > 5:
            log(f"  ... и ещё {len(unexpected_keys) - 5}")
    
    log("✅ Веса загружены (совместимые)")
    
    model.eval()
    
    total_params = sum(p.numel() for p in model.parameters())
    trainable_params = sum(p.numel() for p in model.parameters() if p.requires_grad)
    log(f"Веса загружены: {total_params:,} параметров (тренируемых: {trainable_params:,})")

    log(f"Экспорт с фиксированным размером тайла: {tile_size}×{tile_size}")
    
    dummy_input = torch.randn(1, 3, tile_size, tile_size)
    onnx_path = convert_dir / "restormer.onnx"
    
    log(f"Экспортируем PyTorch → ONNX (opset 18, legacy mode, статический размер {tile_size}×{tile_size}): {onnx_path}")
    torch.onnx.export(
        model,
        dummy_input,
        str(onnx_path),
        opset_version=18,
        input_names=['input'],
        output_names=['output'],
        dynamo=False,
    )

    onnx_size = onnx_path.stat().st_size
    onnx_size_mb = onnx_size / 1024 / 1024
    log(f"ONNX создан: {onnx_size_mb:.1f} MB")
    
    MIN_EXPECTED_SIZE_MB = 80.0
    if onnx_size_mb < MIN_EXPECTED_SIZE_MB:
        raise ValueError(
            f"ONNX файл слишком маленький ({onnx_size_mb:.1f} MB), "
            f"ожидается минимум {MIN_EXPECTED_SIZE_MB} MB. "
            f"Возможно, веса не загружены."
        )
    
    log(f"✅ ONNX размер валиден: {onnx_size_mb:.1f} MB")

    log("Выполняем shape inference перед упрощением...")
    model_onnx = onnx.load(str(onnx_path))
    model_onnx = onnx.shape_inference.infer_shapes(model_onnx)
    
    simp_path = convert_dir / "restormer_simplified.onnx"
    try:
        log("Упрощаем ONNX граф с помощью onnxsim...")
        model_simp, check = simplify(model_onnx)
        if check:
            log("Выполняем shape inference после упрощения...")
            model_simp = onnx.shape_inference.infer_shapes(model_simp)
            onnx.save(model_simp, str(simp_path))
            simp_size = simp_path.stat().st_size
            log(f"ONNX упрощен: {simp_size / 1024 / 1024:.1f} MB")
        else:
            log("onnxsim не подтвердил корректность модели; используется исходный ONNX")
            onnx.save(model_onnx, str(simp_path))
    except Exception as exc:
        log(f"Не удалось упростить ONNX: {exc}; используется исходная модель")
        onnx.save(model_onnx, str(simp_path))

    log("Конвертируем ONNX → NCNN...")
    onnx2ncnn = shutil.which("onnx2ncnn")
    if not onnx2ncnn:
        raise RuntimeError("Команда onnx2ncnn не найдена в PATH")

    param_filename = f"{artifact_basename}.param"
    bin_filename = f"{artifact_basename}.bin"
    param_path = convert_dir / param_filename
    bin_path = convert_dir / bin_filename
    
    subprocess.run(
        [onnx2ncnn, str(simp_path), str(param_path), str(bin_path)],
        check=True,
    )
    
    if not param_path.exists() or not bin_path.exists():
        raise RuntimeError("onnx2ncnn не создал .param/.bin файлы")
    
    bin_size = bin_path.stat().st_size
    bin_size_mb = bin_size / 1024 / 1024
    log(f"NCNN модель создана: .param + .bin ({format_mib(bin_size)} MiB)")
    
    # Restormer — большая модель (~26.1M параметров), проверка минимального размера
    default_min_bin_size_mb = 30.0 if precision == "fp16" else 60.0
    min_bin_size_raw = model_cfg.get("min_bin_size_mb")
    if min_bin_size_raw is not None:
        try:
            min_bin_size = float(min_bin_size_raw)
        except (TypeError, ValueError):
            log(
                "⚠️  Некорректное значение min_bin_size_mb в конфиге Restormer; "
                f"используем порог по умолчанию {default_min_bin_size_mb} MB"
            )
            min_bin_size = default_min_bin_size_mb
        else:
            if min_bin_size <= 0:
                min_bin_size = default_min_bin_size_mb
    else:
        min_bin_size = default_min_bin_size_mb
    
    if bin_size_mb < min_bin_size:
        raise RuntimeError(
            f"NCNN .bin файл слишком маленький ({bin_size_mb:.1f} MB), "
            f"ожидается минимум {min_bin_size:.1f} MB. "
            "Возможно, конвертация провалена."
        )
    
    log(f"✅ NCNN .bin размер валиден: {bin_size_mb:.1f} MB (порог {min_bin_size:.1f} MB)")

    apply_fp16_optimize = precision == "fp16"
    if apply_fp16_optimize:
        log("Оптимизируем NCNN модель с помощью ncnnoptimize (fp16 flow)...")
        ncnnoptimize = shutil.which("ncnnoptimize")
        if not ncnnoptimize:
            raise RuntimeError("Команда ncnnoptimize не найдена в PATH")
        
        optimized_param = convert_dir / f"{artifact_basename}_opt.param"
        optimized_bin = convert_dir / f"{artifact_basename}_opt.bin"
        
        try:
            subprocess.run(
                [ncnnoptimize, str(param_path), str(bin_path), str(optimized_param), str(optimized_bin), "65536"],
                check=True,
            )
            
            if optimized_param.exists() and optimized_bin.exists():
                opt_bin_size = optimized_bin.stat().st_size
                log(f"NCNN модель оптимизирована: {format_mib(opt_bin_size)} MiB")
                shutil.move(str(optimized_param), str(param_path))
                shutil.move(str(optimized_bin), str(bin_path))
                bin_size = opt_bin_size
            else:
                log("⚠️  ncnnoptimize не создал файлы, используем неоптимизированную версию")
        except subprocess.CalledProcessError as exc:
            log(f"⚠️  Ошибка при оптимизации NCNN: {exc}; используем неоптимизированную версию")
    else:
        log("Пропускаем ncnnoptimize для FP32 варианта, оставляем onnx2ncnn результаты как есть")

    log(f"✅ NCNN модель готова ({precision.upper()})")

    metadata: Dict[str, object] = {
        "ncnn": {
            "param_size_bytes": param_path.stat().st_size,
            "bin_size_bytes": bin_size,
            "bin_size_mib": format_mib(bin_size),
            "sha256_param": sha256_of(param_path),
            "sha256_bin": sha256_of(bin_path),
            "tile_size": tile_size,
            "precision": precision,
        },
        "onnx": {
            "path": str(simp_path.name),
            "sha256": sha256_of(simp_path),
            "size_mib": format_mib(simp_path.stat().st_size),
            "operators": collect_onnx_operator_types(simp_path),
        },
        "precision": precision,
    }

    files = [
        {
            "path": param_path,
            "relative": Path("models") / param_filename,
            "include": True,
            "label": "param",
        },
        {
            "path": bin_path,
            "relative": Path("models") / bin_filename,
            "include": True,
            "label": "bin",
        },
        {
            "path": simp_path,
            "relative": Path("restormer_simplified.onnx"),
            "include": False,
            "label": "onnx",
        },
    ]

    return "ncnn", files, metadata



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

    precision_raw = cfg.get("precision")
    precision = None
    if precision_raw is not None:
        precision_str = str(precision_raw).strip().lower()
        if precision_str:
            precision = precision_str
    enabled = parse_bool(cfg.get("enabled", True))

    if key == "zerodcepp_fp16":
        backend, files, metadata = convert_zero_dce(cfg, sources, convert_dir)
    elif key.startswith("restormer_"):
        backend, files, metadata = convert_restormer(cfg, sources, convert_dir)
    else:
        raise RuntimeError(f"Неизвестная модель: {key}")

    staging_dir = WORK_STAGING / f"{cfg['artifact']}_{cfg['version']}"
    if staging_dir.exists():
        shutil.rmtree(staging_dir)
    staging_models_dir = staging_dir / "models"
    staging_models_dir.mkdir(parents=True, exist_ok=True)

    staged_entries = []
    staged_paths = []
    hash_entries: List[Dict[str, Any]] = []
    for descriptor in files:
        src = Path(descriptor["path"])
        relative_path = Path(descriptor.get("relative", src.name))
        include = bool(descriptor.get("include", True))
        label = descriptor.get("label")

        if include:
            dest = staging_dir / relative_path
            dest.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(src, dest)
            staged_paths.append(relative_path)
            size_mb = format_mib(dest.stat().st_size)
            sha_value = sha256_of(dest)
            staged_entries.append(
                {
                    "path": relative_path,
                    "sha256": sha_value,
                    "min_mb": size_mb,
                    "label": label,
                }
            )
            hash_entries.append(
                {
                    "path": relative_path.as_posix(),
                    "sha256": sha_value,
                    "size_mb": size_mb,
                    "label": label,
                    "included": True,
                }
            )
        else:
            size_mb = format_mib(src.stat().st_size)
            sha_value = sha256_of(src)
            hash_entries.append(
                {
                    "path": relative_path.as_posix(),
                    "sha256": sha_value,
                    "size_mb": size_mb,
                    "label": label,
                    "included": False,
                }
            )

    def find_common_prefix(paths: List[Path]) -> Path:
        if not paths:
            return Path(".")
        prefix_parts: List[str] = list(paths[0].parent.parts)
        for current in paths[1:]:
            current_parts = list(current.parent.parts)
            new_prefix: List[str] = []
            for index, part in enumerate(prefix_parts):
                if index < len(current_parts) and current_parts[index] == part:
                    new_prefix.append(part)
                else:
                    break
            prefix_parts = new_prefix
            if not prefix_parts:
                break
        if not prefix_parts:
            return Path(".")
        return Path(*prefix_parts)

    unzipped_root = find_common_prefix(staged_paths)

    file_entries = []
    for entry in staged_entries:
        relative_path: Path = entry["path"]
        if unzipped_root == Path("."):
            visible_path = relative_path
        else:
            visible_path = relative_path.relative_to(unzipped_root)
        file_entries.append(
            {
                "path": visible_path.as_posix(),
                "sha256": entry["sha256"],
                "min_mb": entry["min_mb"],
                "label": entry.get("label"),
            }
        )

    artifact_name = f"{cfg['artifact']}_{cfg['version']}.zip"
    zip_path = DIST_DIR / artifact_name
    if zip_path.exists():
        zip_path.unlink()
    with zipfile.ZipFile(zip_path, "w", compression=zipfile.ZIP_DEFLATED) as zf:
        for relative_path in staged_paths:
            absolute = staging_dir / relative_path
            zf.write(absolute, arcname=relative_path.as_posix())

    artifact_sha = sha256_of(zip_path)
    artifact_size = format_mib(zip_path.stat().st_size)

    dist_copied: List[str] = []
    for relative_path in staged_paths:
        destination = DIST_DIR / relative_path
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(staging_dir / relative_path, destination)
        dist_copied.append(destination.relative_to(DIST_DIR).as_posix())
    if dist_copied:
        log(
            "Файлы моделей размещены в {base}: {items}".format(
                base=DIST_MODELS_DIR,
                items=", ".join(dist_copied),
            )
        )

    return {
        "key": key,
        "display": cfg["name"],
        "artifact_name": artifact_name,
        "artifact_path": zip_path,
        "artifact_sha": artifact_sha,
        "artifact_size": artifact_size,
        "backend": backend,
        "unzipped_root": unzipped_root.as_posix() if unzipped_root != Path(".") else ".",
        "files": file_entries,
        "hash_entries": hash_entries,
        "metadata": metadata,
        "precision": precision,
        "enabled": enabled,
    }


def write_sha_sums(results: List[dict]) -> None:
    sha_path = DIST_DIR / "SHA256SUMS.txt"
    with sha_path.open("w", encoding="utf-8") as fp:
        for result in results:
            fp.write(f"# {result['display']}\n")
            for entry in result.get("hash_entries", []):
                fp.write(f"{entry['sha256']}  {entry['path']}\n")
            fp.write("\n")
    log(f"SHA256SUMS.txt обновлён: {sha_path}")


def write_summary(results: List[dict]) -> None:
    SUMMARY_FILE.parent.mkdir(parents=True, exist_ok=True)
    lines = [
        "| Модель | Backend | Размер (MiB) | SHA-256 | Операторов | Статус |",
        "| --- | --- | --- | --- | --- | --- |",
    ]
    for result in results:
        metadata = result.get("metadata", {})
        backend = result.get("backend", "unknown")
        
        if backend == "tflite":
            tflite_info = metadata.get("tflite") if isinstance(metadata, dict) else None
            if isinstance(tflite_info, dict):
                size_value = tflite_info.get("size_mib")
                size_text = f"{float(size_value):.2f}" if size_value is not None else "—"
                sha_value = tflite_info.get("sha256", "—")
                if len(sha_value) > 16:
                    sha_value = sha_value[:8] + "..."
                op_count = tflite_info.get("op_count", "—")
                smoke_status = tflite_info.get("status", "—")
            else:
                size_text = "—"
                sha_value = "—"
                op_count = "—"
                smoke_status = "—"
        elif backend == "ncnn":
            ncnn_info = metadata.get("ncnn") if isinstance(metadata, dict) else None
            if isinstance(ncnn_info, dict):
                size_value = ncnn_info.get("bin_size_mib")
                size_text = f"{float(size_value):.2f}" if size_value is not None else "—"
                sha_value = ncnn_info.get("sha256_bin", "—")
                if len(sha_value) > 16:
                    sha_value = sha_value[:8] + "..."
                op_count = "—"
                # Разные требования к размеру для разных моделей
                model_name = result.get("key", "").lower()
                if "zerodcepp" in model_name or "zerodce" in model_name:
                    min_size_mib = 0.030  # 30 KB минимум для Zero-DCE++
                elif "restormer" in model_name:
                    min_size_mib = 1.0    # 1 MB минимум для Restormer
                else:
                    min_size_mib = 0.1    # 100 KB по умолчанию
                smoke_status = "OK" if size_value and size_value >= min_size_mib else "FAIL"
            else:
                size_text = "—"
                sha_value = "—"
                op_count = "—"
                smoke_status = "—"
        else:
            size_text = "—"
            sha_value = "—"
            op_count = "—"
            smoke_status = "—"

        lines.append(
            "| {model} | {backend} | {size} | {sha} | {ops} | {status} |".format(
                model=result["display"],
                backend=backend,
                size=size_text,
                sha=sha_value,
                ops=op_count,
                status=smoke_status,
            )
        )
    lines.append("")
    lines.append("### Контрольные суммы")
    for result in results:
        lines.append(f"- **{result['display']}**:")
        for entry in result.get("hash_entries", []):
            suffix = " (в артефакте)" if entry.get("included") else " (не упакован)"
            lines.append(
                "  - `{path}` — `{sha}` ({size:.2f} MiB){suffix}".format(
                    path=entry["path"],
                    sha=entry["sha256"],
                    size=float(entry.get("size_mb", 0.0)),
                    suffix=suffix,
                )
            )
        onnx_meta = result.get("metadata", {}).get("onnx") if isinstance(result.get("metadata"), dict) else None
        if isinstance(onnx_meta, dict):
            operators = onnx_meta.get("operators")
            if operators:
                lines.append("  - Операторы ONNX: " + ", ".join(operators))
    lines.append("")
    lines.append("Файл `SHA256SUMS.txt` записан в `dist/`.")
    SUMMARY_FILE.write_text("\n".join(lines), encoding="utf-8")
    log(f"Summary записан в {SUMMARY_FILE}")


def write_models_lock(results: List[dict]) -> None:
    repository = os.environ.get("GITHUB_REPOSITORY", "")
    if not repository and MODELS_LOCK_PATH.exists():
        try:
            with MODELS_LOCK_PATH.open("r", encoding="utf-8") as fp:
                repository = json.load(fp).get("repository", "")
        except Exception:
            pass

    models_payload = {}
    for result in results:
        entry = {
            "release": RELEASE_TAG,
            "asset": result["artifact_name"],
            "unzipped": result.get("unzipped_root", "."),
            "sha256": result["artifact_sha"],
            "backend": result["backend"],
            "min_mb": result["artifact_size"],
            "files": result["files"],
            "hashes": result.get("hash_entries", []),
            "metadata": result.get("metadata", {}),
        }
        precision = result.get("precision")
        if precision:
            entry["precision"] = precision
        entry["enabled"] = bool(result.get("enabled", True))
        models_payload[result["key"]] = entry
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
