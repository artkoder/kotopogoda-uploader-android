# Автоматизация models.lock.json в CI

## Описание изменений

Реализована автоматическая генерация и валидация файла `models.lock.json` в GitHub Actions workflow `Prepare models`.

## Что добавлено

### 1. Скрипт генерации (`tools/gen_models_lock.py`)

Создан Python-скрипт для генерации lock-файла на основе фактических файлов моделей в `dist/models/`.

**Возможности:**
- Вычисление SHA-256 хешей для всех файлов NCNN моделей (`.param`, `.bin`)
- Генерация метахеша для `SHA256SUMS.txt` (покрытие ONNX файлов)
- Поддержка версионирования через `api_contract_version`
- Детальная диагностика ошибок (отсутствующие файлы, некорректная структура)

**Использование:**
```bash
MODELS_DIST=dist MODELS_CONTRACT_VERSION=v1.4.1 python tools/gen_models_lock.py
```

### 2. Обновление workflow (`.github/workflows/prepare_models.yml`)

Добавлены следующие шаги после публикации релиза:

1. **Unpack models to dist/models/** — распаковка ZIP-артефактов в `dist/models/`
2. **Ensure SHA256SUMS.txt** — создание детерминированного списка контрольных сумм
3. **Generate models.lock.json** — запуск генератора lock-файла
4. **Validate models.lock.json** — проверка корректности структуры и хешей
5. **Sync lock to repo root** — копирование в корень репозитория
6. **Create PR** — автоматическое создание PR с обновленным lock-файлом
7. **Upload artifact** — загрузка артефакта `models-v1` с моделями и lock-файлом

### 3. Документация

- **`tools/README.md`** — описание утилиты и её использования
- **`docs/models.lock.sample.json`** — пример выходного формата для справки

## Формат models.lock.json (v1.4.1)

```json
{
  "api_contract_version": "v1.4.1",
  "entries": [
    {
      "id": "zerodcepp_fp16",
      "backend": "ncnn",
      "files": [
        { "path": "models/zerodcepp_fp16.param", "sha256": "..." },
        { "path": "models/zerodcepp_fp16.bin", "sha256": "..." }
      ]
    },
    {
      "id": "restormer_fp16",
      "backend": "ncnn",
      "files": [
        { "path": "models/restormer_fp16.param", "sha256": "..." },
        { "path": "models/restormer_fp16.bin", "sha256": "..." }
      ]
    },
    {
      "id": "metadata",
      "backend": "METADATA",
      "jq_sha256sum": "<sha256 of SHA256SUMS.txt>"
    }
  ]
}
```

## Валидация

Lock-файл автоматически валидируется по следующим критериям:

1. ✅ Структура: `entries` должен быть списком
2. ✅ Версия: `api_contract_version` === `"v1.4.1"`
3. ✅ Backend: только `"ncnn"`, `"tflite"` или `"METADATA"`
4. ✅ Файлы: все указанные файлы существуют в `dist/`
5. ✅ Хеши: SHA-256 совпадают с фактическими
6. ✅ Метаданные: `jq_sha256sum` — валидный 64-символьный хеш или пустая строка

При несоответствии workflow завершится с ошибкой, релиз не будет опубликован.

## Workflow для разработчика

### Обновление моделей

1. Запустить workflow `Prepare models` вручную через GitHub Actions
2. CI автоматически:
   - Соберет модели (NCNN + ONNX)
   - Создаст ZIP-артефакты и опубликует релиз
   - Распакует модели в `dist/models/`
   - Сгенерирует и проверит `models.lock.json`
   - Создаст PR с обновленным lock-файлом
3. Проверить и смержить автоматический PR

### Проверка локально

```bash
# Создать тестовую структуру
mkdir -p test_dist/models
echo "test" > test_dist/models/zerodcepp_fp16.param
echo "test" > test_dist/models/zerodcepp_fp16.bin
echo "test" > test_dist/models/restormer_fp16.param
echo "test" > test_dist/models/restormer_fp16.bin
echo "checksums" > test_dist/SHA256SUMS.txt

# Сгенерировать lock
MODELS_DIST=test_dist python tools/gen_models_lock.py

# Проверить результат
cat test_dist/models.lock.json
```

## Критерии приёмки

- ✅ Workflow `Prepare models` выполняется успешно
- ✅ Артефакт `models-v1` содержит `dist/models/**`, `dist/SHA256SUMS.txt`, `dist/models.lock.json`
- ✅ Создается PR с обновленным корневым `models.lock.json`
- ✅ Валидация падает при отсутствии файлов или несовпадении хешей
- ✅ При изменении моделей lock автоматически пересчитывается

## Совместимость

### Старый формат (deprecated)

Старый формат `models.lock.json` (с `repository`, `models.*.release`, `models.*.asset`) больше не поддерживается для генерации, но может существовать в старых ветках. Приложение должно обрабатывать оба формата (graceful degradation реализовано в PR #575).

### Новый формат (v1.4.1)

Единственный формат, генерируемый CI. Является source of truth для приложения.

## Ограничения

- В lock-файл попадают только файлы, используемые рантаймом: NCNN `.param` и `.bin`
- ONNX файлы покрыты метахешем `jq_sha256sum` (SHA-256 файла `SHA256SUMS.txt`)
- Версия контракта зафиксирована: `v1.4.1`
- Модели: `zerodcepp_fp16`, `restormer_fp16` (hardcoded в генераторе)

## Дальнейшие улучшения

- Автоматическое обнаружение моделей из `dist/models/` (без hardcode списка)
- Поддержка TensorFlow Lite моделей
- Версионирование lock-файла через Git tags
- Автоматическое создание GitHub Release для lock-файла
