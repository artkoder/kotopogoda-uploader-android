#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WORK_DIR="${WORK_DIR:-"$ROOT_DIR/.work/models"}"
DOWNLOAD_DIR="$WORK_DIR/downloads"
STAGING_DIR="$WORK_DIR/staging"
ARTIFACT_DIR="$WORK_DIR/artifacts"
SUMMARY_FILE="${GITHUB_STEP_SUMMARY:-"$WORK_DIR/SUMMARY.md"}"
RELEASE_TAG="${MODELS_RELEASE_TAG:-models-v1}"
ZERO_DCE_VERSION="${ZERO_DCE_VERSION:-v1}"
RESTORMER_VERSION="${RESTORMER_VERSION:-v1}"
ZERO_DCE_BACKEND_CHOICE="${ZERO_DCE_BACKEND:-auto}"
RESTORMER_BACKEND_CHOICE="${RESTORMER_BACKEND:-auto}"

mkdir -p "$DOWNLOAD_DIR" "$STAGING_DIR" "$ARTIFACT_DIR"

cleanup() {
  rm -rf "$WORK_DIR/tmp" 2>/dev/null || true
}
trap cleanup EXIT

log() {
  printf '%s\n' "$*" >&2
}

log_notice() {
  printf '::notice::%s\n' "$*"
}

fail() {
  printf '::error::%s\n' "$*" >&2
  exit 1
}

require_cmd() {
  local cmd
  for cmd in "$@"; do
    command -v "$cmd" >/dev/null 2>&1 || fail "Команда '$cmd' недоступна"
  done
}

require_cmd curl sha256sum zip gh

resolve_source() {
  local dest="$1" url_var="$2" path_var="$3" description="$4"
  local url="${!url_var:-}" path="${!path_var:-}"
  if [[ -n "$path" ]]; then
    [[ -f "$path" ]] || fail "Файл '$path' для $description не найден"
    cp "$path" "$dest"
    return 0
  fi
  if [[ -n "$url" ]]; then
    log "Скачивание $description из $url"
    curl -L --retry 3 --fail --silent --show-error "$url" -o "$dest"
    return 0
  fi
  return 1
}

choose_backend() {
  local model="$1" choice="$2" tflite_available="$3" ncnn_available="$4"
  case "$choice" in
    auto)
      if [[ "$tflite_available" == "1" ]]; then
        echo "tflite"
      elif [[ "$ncnn_available" == "1" ]]; then
        echo "ncnn"
      else
        echo "" 
      fi
      ;;
    tflite)
      [[ "$tflite_available" == "1" ]] && echo "tflite" || { log "TFLite для $model недоступен"; echo ""; }
      ;;
    ncnn)
      [[ "$ncnn_available" == "1" ]] && echo "ncnn" || { log "NCNN для $model недоступен"; echo ""; }
      ;;
    *)
      fail "Неизвестный backend '$choice' для $model"
      ;;
  esac
}

prepare_zero_dce() {
  local base_name="zerodcepp_fp16"
  local tflite_path="$DOWNLOAD_DIR/${base_name}.tflite"
  local ncnn_param="$DOWNLOAD_DIR/${base_name}.param"
  local ncnn_bin="$DOWNLOAD_DIR/${base_name}.bin"
  local has_tflite=0
  local has_ncnn=0

  if resolve_source "$tflite_path" ZERO_DCE_TFLITE_URL ZERO_DCE_TFLITE_PATH "Zero-DCE++ TFLite"; then
    has_tflite=1
  fi
  if resolve_source "$ncnn_param" ZERO_DCE_NCNN_PARAM_URL ZERO_DCE_NCNN_PARAM_PATH "Zero-DCE++ NCNN param" && \
     resolve_source "$ncnn_bin" ZERO_DCE_NCNN_BIN_URL ZERO_DCE_NCNN_BIN_PATH "Zero-DCE++ NCNN bin"; then
    has_ncnn=1
  fi

  local backend
  backend=$(choose_backend "Zero-DCE++" "$ZERO_DCE_BACKEND_CHOICE" "$has_tflite" "$has_ncnn")
  [[ -n "$backend" ]] || fail "Не удалось определить backend для Zero-DCE++"

  local package_dir="$STAGING_DIR/${base_name}_${ZERO_DCE_VERSION}"
  rm -rf "$package_dir"
  mkdir -p "$package_dir/models"

  case "$backend" in
    tflite)
      cp "$tflite_path" "$package_dir/models/${base_name}.tflite"
      ;;
    ncnn)
      cp "$ncnn_param" "$package_dir/models/${base_name}.param"
      cp "$ncnn_bin" "$package_dir/models/${base_name}.bin"
      ;;
  esac

  local archive="$ARTIFACT_DIR/${base_name}_${ZERO_DCE_VERSION}.zip"
  (cd "$package_dir" && zip -rq "$archive" .)
  log_notice "Zero-DCE++ backend: $backend"
  echo "$backend" > "$ARTIFACT_DIR/${base_name}.backend"
}

prepare_restormer() {
  local base_name="restormer_fp16"
  local tflite_path="$DOWNLOAD_DIR/${base_name}.tflite"
  local ncnn_param="$DOWNLOAD_DIR/${base_name}.param"
  local ncnn_bin="$DOWNLOAD_DIR/${base_name}.bin"
  local has_tflite=0
  local has_ncnn=0

  if resolve_source "$tflite_path" RESTORMER_TFLITE_URL RESTORMER_TFLITE_PATH "Restormer TFLite"; then
    has_tflite=1
  fi
  if resolve_source "$ncnn_param" RESTORMER_NCNN_PARAM_URL RESTORMER_NCNN_PARAM_PATH "Restormer NCNN param" && \
     resolve_source "$ncnn_bin" RESTORMER_NCNN_BIN_URL RESTORMER_NCNN_BIN_PATH "Restormer NCNN bin"; then
    has_ncnn=1
  fi

  local backend
  backend=$(choose_backend "Restormer" "$RESTORMER_BACKEND_CHOICE" "$has_tflite" "$has_ncnn")
  [[ -n "$backend" ]] || fail "Не удалось определить backend для Restormer"

  local package_dir="$STAGING_DIR/${base_name}_${RESTORMER_VERSION}"
  rm -rf "$package_dir"
  mkdir -p "$package_dir/models"

  case "$backend" in
    tflite)
      cp "$tflite_path" "$package_dir/models/${base_name}.tflite"
      ;;
    ncnn)
      cp "$ncnn_param" "$package_dir/models/${base_name}.param"
      cp "$ncnn_bin" "$package_dir/models/${base_name}.bin"
      ;;
  esac

  local archive="$ARTIFACT_DIR/${base_name}_${RESTORMER_VERSION}.zip"
  (cd "$package_dir" && zip -rq "$archive" .)
  log_notice "Restormer backend: $backend"
  echo "$backend" > "$ARTIFACT_DIR/${base_name}.backend"
}

prepare_models() {
  prepare_zero_dce
  prepare_restormer

  (cd "$ARTIFACT_DIR" && sha256sum *.zip > SHA256SUMS.txt)

  if ! gh release view "$RELEASE_TAG" >/dev/null 2>&1; then
    log "Релиз $RELEASE_TAG не найден, создаём"
    gh release create "$RELEASE_TAG" --notes "Автоматическая сборка моделей" >/dev/null
  fi

  local assets=("$ARTIFACT_DIR"/*.zip "$ARTIFACT_DIR"/SHA256SUMS.txt)
  gh release upload "$RELEASE_TAG" "${assets[@]}" --clobber

  local repository="${GITHUB_REPOSITORY:-}" base_url=""
  if [[ -n "$repository" ]]; then
    base_url="https://github.com/$repository/releases/download/$RELEASE_TAG"
  fi

  {
    echo "# Model artifacts ($RELEASE_TAG)"
    echo
    echo "| Модель | Backend | SHA256 | Ссылка |"
    echo "| --- | --- | --- | --- |"
    for archive in "$ARTIFACT_DIR"/*.zip; do
      local name
      name=$(basename "$archive")
      local base
      base="${name%.zip}"
      local core
      core="${base%_*}"
      local backend_file="$ARTIFACT_DIR/${core}.backend"
      local backend_value
      backend_value="$(cat "$backend_file" 2>/dev/null || echo "unknown")"
      local checksum
      checksum=$(sha256sum "$archive" | awk '{print $1}')
      local link
      if [[ -n "$base_url" ]]; then
        link="$base_url/$name"
      else
        link="(локально)"
      fi
      echo "| $base | $backend_value | $checksum | $link |"
    done
    echo
    echo '\`SHA256SUMS.txt\` загружен в релиз.'
  } >> "$SUMMARY_FILE"
}

prepare_models
