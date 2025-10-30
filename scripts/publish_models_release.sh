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
