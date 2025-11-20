#!/usr/bin/env bash
set -euo pipefail

log() {
  local level="$1"
  shift
  printf '[%s] %s\n' "$level" "$*" >&2
}

fatal() {
  log "ERROR" "$*"
  exit 1
}

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$REPO_ROOT"

DIST_DIR="dist"
RELEASE_TAG="${MODELS_RELEASE_TAG:-models-v1}"
RELEASE_TITLE="${MODELS_RELEASE_TITLE:-Models release (${RELEASE_TAG})}"
RELEASE_NOTES="${MODELS_RELEASE_NOTES:-Автоматический релиз моделей}"
SHA_FILE="$DIST_DIR/SHA256SUMS.txt"

command -v gh >/dev/null 2>&1 || fatal "CLI GitHub (gh) не найден. Установите его и выполните аутентификацию."
command -v git >/dev/null 2>&1 || fatal "Git не найден."

if [[ ! -d "$DIST_DIR" ]]; then
  fatal "Каталог '$DIST_DIR' не найден. Сначала соберите артефакты."
fi

mapfile -t ZIP_FILES < <(find "$DIST_DIR" -maxdepth 1 -type f -name '*.zip' | sort)
if [[ "${#ZIP_FILES[@]}" -eq 0 ]]; then
  fatal "В каталоге '$DIST_DIR' отсутствуют zip-артефакты."
fi

if [[ ! -f "$SHA_FILE" ]]; then
  fatal "Файл контрольных сумм '$SHA_FILE' не найден."
fi

log INFO "Проверяем артефакты моделей"
python3 - <<'PY'
import json
import zipfile
from pathlib import Path

dist_dir = Path("dist")
models_lock = Path("models.lock.json")
if not models_lock.exists():
    raise SystemExit("models.lock.json не найден, повторите подготовку моделей")

data = json.loads(models_lock.read_text(encoding="utf-8"))
models = data.get("models", {})

# Проверяем Zero-DCE++
zero = models.get("zerodcepp_fp16")
if zero is None:
    raise SystemExit("В models.lock.json отсутствует запись zerodcepp_fp16")

metadata = zero.get("metadata") or {}
ncnn_meta = metadata.get("ncnn") or {}
bin_size_mib = ncnn_meta.get("bin_size_mib")
MIN_ZERO_DCE_SIZE_MIB = 0.030
if bin_size_mib is None or bin_size_mib < MIN_ZERO_DCE_SIZE_MIB:
    raise SystemExit(
        f"Zero-DCE++ NCNN .bin файл имеет недопустимый размер: {bin_size_mib} MiB "
        f"(ожидается ≥{MIN_ZERO_DCE_SIZE_MIB} MiB)"
    )

asset_name = zero.get("asset")
if not asset_name:
    raise SystemExit("В записи zerodcepp_fp16 отсутствует имя артефакта")

zip_path = dist_dir / asset_name
if not zip_path.exists():
    raise SystemExit(f"Архив {zip_path} не найден")

with zipfile.ZipFile(zip_path, "r") as archive:
    file_names = sorted([info.filename for info in archive.infolist() if not info.is_dir()])

expected = ["models/zerodcepp_fp16.bin", "models/zerodcepp_fp16.param"]
if file_names != expected:
    raise SystemExit(
        f"Архив {asset_name} должен содержать {expected}, найдено: {file_names}"
    )

print(f"✅ Zero-DCE++: архив NCNN в норме (размер .bin: {bin_size_mib:.4f} MiB)")

# Проверяем Restormer
restormer = models.get("restormer_fp16")
if restormer is None:
    raise SystemExit("В models.lock.json отсутствует запись restormer_fp16")

backend = restormer.get("backend")
if backend != "ncnn":
    raise SystemExit(f"Restormer должен использовать backend=ncnn, получен: {backend}")

metadata = restormer.get("metadata") or {}
ncnn_meta = metadata.get("ncnn") or {}
bin_size_mib = ncnn_meta.get("bin_size_mib")
MIN_RESTORMER_SIZE_MIB = 30.0
if bin_size_mib is None or bin_size_mib < MIN_RESTORMER_SIZE_MIB:
    raise SystemExit(
        f"Restormer NCNN .bin файл имеет недопустимый размер: {bin_size_mib} MiB "
        f"(ожидается ≥{MIN_RESTORMER_SIZE_MIB} MiB)"
    )

asset_name = restormer.get("asset")
if not asset_name:
    raise SystemExit("В записи restormer_fp16 отсутствует имя артефакта")

zip_path = dist_dir / asset_name
if not zip_path.exists():
    raise SystemExit(f"Архив {zip_path} не найден")

with zipfile.ZipFile(zip_path, "r") as archive:
    file_names = sorted([info.filename for info in archive.infolist() if not info.is_dir()])

expected = ["models/restormer_fp16.bin", "models/restormer_fp16.param"]
if file_names != expected:
    raise SystemExit(
        f"Архив {asset_name} должен содержать {expected}, найдено: {file_names}"
    )

print(f"✅ Restormer: архив NCNN в норме (размер .bin: {bin_size_mib:.4f} MiB)")

# Проверяем Restormer FP32 (артефакт отключён по умолчанию)
restormer_fp32 = models.get("restormer_fp32")
if restormer_fp32 is None:
    raise SystemExit("В models.lock.json отсутствует запись restormer_fp32")
precision_fp32 = (restormer_fp32.get("precision") or "").lower()
if precision_fp32 and precision_fp32 != "fp32":
    raise SystemExit(f"restormer_fp32 должен иметь precision=fp32, получено: {precision_fp32}")
asset_name = restormer_fp32.get("asset")
if not asset_name:
    raise SystemExit("В записи restormer_fp32 отсутствует имя артефакта")
zip_path = dist_dir / asset_name
if not zip_path.exists():
    raise SystemExit(f"Архив {zip_path} не найден")
metadata = restormer_fp32.get("metadata") or {}
ncnn_meta = metadata.get("ncnn") or {}
bin_size_mib = ncnn_meta.get("bin_size_mib")
MIN_RESTORMER_FP32_SIZE_MIB = 60.0
if bin_size_mib is None or bin_size_mib < MIN_RESTORMER_FP32_SIZE_MIB:
    raise SystemExit(
        f"Restormer FP32 NCNN .bin имеет недопустимый размер: {bin_size_mib} MiB "
        f"(ожидается ≥{MIN_RESTORMER_FP32_SIZE_MIB} MiB)"
    )
with zipfile.ZipFile(zip_path, "r") as archive:
    file_names = sorted([info.filename for info in archive.infolist() if not info.is_dir()])
expected = ["models/restormer_fp32.bin", "models/restormer_fp32.param"]
if file_names != expected:
    raise SystemExit(
        f"Архив {asset_name} должен содержать {expected}, найдено: {file_names}"
    )
print(f"✅ Restormer FP32: архив NCNN готов (размер .bin: {bin_size_mib:.4f} MiB, enabled={restormer_fp32.get('enabled', False)})")
PY

log INFO "Используем тег релиза: $RELEASE_TAG"
log INFO "Проверяем наличие релиза $RELEASE_TAG..."
if ! gh release view "$RELEASE_TAG" >/dev/null 2>&1; then
  log INFO "Релиз отсутствует. Создаём $RELEASE_TAG."
  gh release create "$RELEASE_TAG" --title "$RELEASE_TITLE" --notes "$RELEASE_NOTES" || fatal "Не удалось создать релиз '$RELEASE_TAG'."
fi

log INFO "Загружаем артефакты в релиз..."
if ! gh release upload "$RELEASE_TAG" "$SHA_FILE" "${ZIP_FILES[@]}" --clobber; then
  fatal "Команда gh release upload завершилась с ошибкой."
fi

log INFO "Обновляем GITHUB_STEP_SUMMARY..."
SUMMARY_FILE="${GITHUB_STEP_SUMMARY:-}"
if [[ -z "$SUMMARY_FILE" ]]; then
  log WARNING "Переменная GITHUB_STEP_SUMMARY не задана, пропускаем вывод итогового Markdown."
else
  REPO_SLUG="${GITHUB_REPOSITORY:-}"
  if [[ -z "$REPO_SLUG" ]]; then
    origin_url="$(git remote get-url origin 2>/dev/null || true)"
    if [[ "$origin_url" =~ github.com[:/](.+)\.git$ ]]; then
      REPO_SLUG="${BASH_REMATCH[1]}"
    else
      fatal "Не удалось определить репозиторий GitHub из URL '$origin_url'."
    fi
  fi

  {
    echo "## ${RELEASE_TAG} релиз"
    echo
    echo "| Файл | SHA256 |"
    echo "| --- | --- |"
    while read -r sha path _; do
      [[ -z "$sha" || -z "$path" ]] && continue
      [[ $sha == \#* ]] && continue
      file="${path##*/}"
      url="https://github.com/$REPO_SLUG/releases/download/$RELEASE_TAG/$file"
      echo "| [${file}](${url}) | \`$sha\` |"
    done < "$SHA_FILE"
    echo
  } >> "$SUMMARY_FILE"
fi

if git status --short -- "models.lock.json" | grep -q "^ M"; then
  log INFO "Файл models.lock.json изменён и готов к PR."
else
  log INFO "Файл models.lock.json не изменился в этом запуске."
fi

log INFO "Скрипт успешно завершён: артефакты загружены."
