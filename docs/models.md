# Модели и пайплайн обновления

## One-click workflow
1. Откройте GitHub → Actions → **Prepare models** и нажмите **Run workflow**. Workflow не принимает входных параметров: достаточно одного клика, чтобы взять зафиксированные источники, собрать архивы и обновить локфайлы.\
2. Шаг `prepare` читает `scripts/model_sources.lock.json`, в котором заданы репозитории, коммиты и дополнительные URL. Файл играет роль «freeze»-конфигурации: любые изменения версий в workflow возможны только после обновления этого локфайла в репозитории.\
3. Скрипт `scripts/prepare_models.py` автоматически:\
   - скачивает и кеширует исходники и веса по `model_sources.lock.json`,
   - конвертирует Zero-DCE++ и Restormer в NCNN (`backend=ncnn`) через ONNX, при этом для Restormer формирует **две** вариации: включенную в приложение FP16 и отключённую FP32, лежащую в отдельном архиве,
   - упаковывает артефакты `<artifact>_v2.zip` в `dist/` и формирует `SHA256SUMS.txt`,
   - пересоздаёт `models.lock.json`, выставляя backend'ы, `precision`, флаги `enabled` и SHA-256 для архива и вложенных файлов (.param/.bin).
4. В `GITHUB_STEP_SUMMARY` появляется таблица с перечислением моделей, их backend'ов, размером артефакта и контрольной суммой. Это итог one-click запуска, который используется перед публикацией релиза.

## `model_sources.lock.json`
- Расположен в `scripts/model_sources.lock.json` и версионируется вместе с проектом.
- Для каждой модели задаёт `artifact`, номер версии (`version`), точность (`precision`), флаг поставки (`enabled`), а также список источников (`sources`) — файлы или архивы, которые нужно скачать на фиксированных коммитах.
- Workflow и локальный запуск `scripts/prepare_models.py` берут данные только из этого файла. Меняя веса или код, сначала обновите `model_sources.lock.json`, а затем перезапустите подготовку моделей.

## `models.lock.json`
- Создаётся автоматически после сборки моделей, ручное редактирование не требуется.
- Содержит:
  - `release` и `asset` (название zip-файла для релиза `models-v2`),
  - `sha256` архива, рассчитанный скриптом,
  - ожидаемый `backend` (`ncnn` для обеих моделей),
  - список файлов с собственными SHA-256 и минимальным размером в MiB (.param/.bin для каждой модели),
  - дополнительные поля `precision` и `enabled` — именно по ним Gradle-задача `fetchModels`, а также runtime-инсталлятор и верификатор чек-сумм понимают, какие модели нужно скачивать/копировать в APK.
- Этот файл читает Gradle-задача `fetchModels` и проверяет контрольные суммы во время сборки приложения. Отключённые модели (например, `restormer_fp32`) пропускаются, поэтому APK продолжает включать только актуальные FP16 веса, а CPU-фолбэк теперь тоже использует `restormer_fp16`.

## Публикация релиза `models-v2`
1. После успешного one-click workflow выполните `scripts/publish_models_release.sh` (в GitHub Actions или локально). Скрипт ожидает готовые артефакты в `dist/`.
2. Скрипт проверяет наличие релиза `models-v2` и создаёт его при необходимости, загружает все `*_v2.zip` и `SHA256SUMS.txt` через `gh release upload --clobber`.
3. В `GITHUB_STEP_SUMMARY` автоматически добавляется Markdown с таблицей файлов и их SHA-256, а затем создаётся коммит `Update models.lock for models-v2 release` с обновлённым `models.lock.json`.

## Локальная проверка
1. Выполните `./gradlew assembleDebug`. Задача `preBuild` автоматически зависит от `fetchModels`, поэтому Gradle скачает артефакты из релиза, проверит SHA-256 и распакует их в `build/models`. В логах теперь видно, что `restormer_fp32` пропускается (enabled=false).
2. При успешной проверке в выводе Gradle появятся сообщения `ok=true` для каждого файла. Любая рассинхронизация SHA или размеров приведёт к ошибке сборки.

## Логи приложения
1. Запустите приложение и выполните улучшение фото, затем соберите логи: `adb logcat -s Enhance`.
2. События `enhance_result` и `enhance_metrics` содержат поля:
   - `backend` — фактический выбор `ncnn`,
   - `delegate_actual` — реальный delegate, задействованный на устройстве,
   - `zero_dce_sha256` / `restormer_sha256` — SHA-256 загруженных NCNN моделей (.param/.bin).
3. Значения SHA должны совпадать с `models.lock.json`; несовпадение сигнализирует о проблеме с поставкой весов.

## Лицензии и цитаты
- **Zero-DCE++** — Chongyi Li et al., “Zero-Reference Deep Curve Estimation (Zero-DCE++) for Low-Light Image Enhancement”, 2021. Официальный репозиторий: <https://github.com/Li-Chongyi/Zero-DCE_extension> (MIT License). Пожалуйста, соблюдайте условия лицензии и указывайте авторов в документации продукта.
- **Restormer** — Swin Transformer for Image Restoration (Swz30 et al., 2021). Репозиторий: <https://github.com/swz30/Restormer> (MIT License). Используйте веса согласно MIT License с указанием первоисточника.
