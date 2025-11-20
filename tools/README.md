# Tools для сборки моделей

## gen_models_lock.py

Генератор `models.lock.json` в **runtime-формате** для приложения.

### Назначение

Скрипт создаёт упрощённый lock-файл для использования приложением во время выполнения. Этот формат содержит только необходимую информацию для загрузки и проверки моделей.

### Формат runtime lock-файла

```json
{
  "api_contract_version": "v1.4.1",
  "entries": [
    {
      "id": "zerodcepp_fp16",
      "backend": "ncnn",
      "files": [
        {"path": "models/zerodcepp_fp16.param", "sha256": "..."},
        {"path": "models/zerodcepp_fp16.bin", "sha256": "..."}
      ]
    },
    {
      "id": "restormer_fp16",
      "backend": "ncnn",
      "files": [
        {"path": "models/restormer_fp16.param", "sha256": "..."},
        {"path": "models/restormer_fp16.bin", "sha256": "..."}
      ]
    },
    {
      "id": "metadata",
      "backend": "METADATA",
      "jq_sha256sum": "<SHA256 от dist/SHA256SUMS.txt>"
    }
  ]
}
```

### Источник данных

Скрипт читает основной `models.lock.json` из корня репозитория и автоматически
берёт все модели с `enabled=true` и `backend=ncnn`. Благодаря этому добавление
новых моделей не требует изменений в коде инструмента — достаточно обновить
`models.lock.json` и запустить подготовку моделей.

### Использование

```bash
# Из корня проекта
export MODELS_DIST=dist
export MODELS_CONTRACT_VERSION=v1.4.1
python tools/gen_models_lock.py
```

### Переменные окружения

- `MODELS_DIST` — путь к директории dist (по умолчанию: `dist`)
- `MODELS_CONTRACT_VERSION` — версия API-контракта (по умолчанию: `v1.4.1`)
- `MODELS_LOCK_PATH` — путь к исходному `models.lock.json` (по умолчанию файл в корне репозитория).
  Для обратной совместимости доступна переменная `MODELS_LOCK_JSON` с тем же назначением.

### Выходной файл

`dist/models.lock.json` — runtime lock-файл для приложения

### Проверка

Скрипт завершается с кодом 2, если:
- Директория `dist/models` не существует
- Какой-либо файл модели отсутствует

### Отличие от models.lock.json в корне репозитория

- **`models.lock.json`** (корень) — богатый формат для CI/релиза с метаданными, размерами, ссылками на release assets
- **`dist/models.lock.json`** — упрощённый runtime-формат для приложения с минимальным набором полей

Оба файла генерируются автоматически в CI workflow.
