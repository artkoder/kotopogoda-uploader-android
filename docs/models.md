# Модели и пайплайн обновления

## Обзор процесса
1. `models.lock.json` хранит список поддерживаемых пакетов с весами, ожидаемым backend'ом и контрольными суммами файлов, которые Gradle-задача `fetchModels` использует для валидации загрузок.\
2. GitHub Actions workflow `Prepare models` вызывает `scripts/prepare_models.sh`, который собирает архивы Zero-DCE++ и Restormer, вычисляет SHA-256 и загружает артефакты в релиз `models-v1`.\
3. Мобильное приложение при выполнении enhancement-логики (`ViewerViewModel`) проверяет фактический backend (`delegate_actual`) и экспортирует `zero_dce_sha256`/`restormer_sha256`, что облегчает полевую диагностику и даёт данные для Self-check.

## Запуск workflow «Prepare models»
1. Откройте GitHub → Actions → **Prepare models** → **Run workflow**.\
2. Укажите при необходимости URL'ы артефактов или конкретные версии (`zero_dce_version`, `restormer_version`).\
3. Workflow выполняет шаг `Prepare model artifacts`, который: 
   - скачивает/копирует веса (curl), 
   - формирует архивы `zerodcepp_fp16_<version>.zip` и `restormer_fp16_<version>.zip`, 
   - публикует их в release `models-v1` и обновляет `SHA256SUMS.txt`.\
4. Итоговая сводка доступна в `GITHUB_STEP_SUMMARY`, где перечислены выбранные backend'ы и контрольные суммы.

## Обновление `models.lock.json`
1. После публикации новых архивов скачайте их локально и вычислите SHA-256 (`sha256sum <file>.zip`).\
2. Обновите соответствующие записи в `models.lock.json`: имя архива, ожидаемый backend, `sha256` для каждого внутреннего файла и минимальный размер.\
3. Создайте PR c изменениями и дождитесь прохождения `Self-check` (см. ниже).\
4. QA проверяет устройство: при запуске улучшения фотографий в логах события `Enhance` должны содержать `delegate_actual`, `zero_dce_sha256` и `restormer_sha256` (см. раздел «Проверка интеграции»).

## Требования к GitHub-токену
- Workflow использует `gh release` с правами `contents: write`. 
- Для локального запуска `scripts/prepare_models.sh` нужно экспортировать `GH_TOKEN`/`GITHUB_TOKEN` со scope `repo`. 
- При работе от имени GitHub Actions достаточно встроенного `${{ secrets.GITHUB_TOKEN }}`.

## Локальная проверка
1. Выполните `./gradlew fetchModels` из корня проекта.\
2. Задача скачает каждый архив из релиза, проверит его SHA-256 и размер, распакует содержимое в `build/models`.\
3. В логе Gradle появится `ok=true` — это сигнал успешной проверки. Ошибка SHA-256 или отсутствие файлов приведёт к падению задачи.

## Проверка интеграции и Self-check
1. **Logcat.** Запустите приложение, выполните улучшение фото и соберите `adb logcat -s Enhance`. В событиях `enhance_result` и `enhance_metrics` присутствуют поля `delegate_actual`, `zero_dce_sha256` и `restormer_sha256`; их значения должны совпадать с обновлёнными весами.\
2. **Self-check.** Внутренний сценарий QA «Self-check» агрегирует последние события улучшения и сводит хэши в универсальное поле `model_sha256`, которое сверяется с контрольными суммами из `models.lock.json`. Перед релизом убедитесь, что Self-check не показывает расхождений.

## Лицензии и цитаты
- **Zero-DCE++** — Chongyi Li et al., “Zero-Reference Deep Curve Estimation (Zero-DCE++) for Low-Light Image Enhancement”, 2021. Официальный репозиторий: <https://github.com/Li-Chongyi/Zero-DCE_extension> (MIT License). Пожалуйста, соблюдайте условия лицензии и указывайте авторов в документации продукта.
- **Restormer** — Swin Transformer for Image Restoration (Swz30 et al., 2021). Репозиторий: <https://github.com/swz30/Restormer> (MIT License). Используйте веса согласно MIT License с указанием первоисточника.
