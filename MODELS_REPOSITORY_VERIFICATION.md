# Верификация репозитория моделей

## Дата проверки
2025-11-11

## Проблема (из тикета)
`:fetchModels` скачивал модели с `github.com/kotopogoda` вместо `github.com/artkoder`, что приводило к ошибке 404.

## Диагностика

### 1. models.lock.json
✅ **ПРОВЕРЕНО**: Содержит правильный репозиторий
```json
"repository": "artkoder/kotopogoda-uploader-android"
```

### 2. build.gradle.kts
✅ **ПРОВЕРЕНО**: Имеет усиленный fallback-механизм (строки 83-87):
```kotlin
val repository = overrideRepository.orNull?.takeIf { it.isNotBlank() }
    ?: System.getenv("MODELS_REPOSITORY")?.takeIf { it.isNotBlank() }
    ?: repoFromLock
    ?: System.getenv("GITHUB_REPOSITORY")?.takeIf { it.isNotBlank() }
    ?: "artkoder/kotopogoda-uploader-android"
```

### 3. CI Workflow
✅ **ПРОВЕРЕНО**: `android-debug-apk.yml` передаёт env переменную (строка 84):
```yaml
- name: Build debug APK
  env:
    MODELS_REPOSITORY: ${{ github.repository }}
  run: gradle --no-daemon assembleDebug
```

## Тестирование

### Локальный тест fetchModels
```bash
$ ./gradlew :fetchModels
> Task :fetchModels
Модель 'restormer_fp32' отключена (enabled=false), пропускаем скачивание
Скачивание https://github.com/artkoder/kotopogoda-uploader-android/releases/download/models-v1/zerodcepp_fp16_v1.zip
Скачивание https://github.com/artkoder/kotopogoda-uploader-android/releases/download/models-v1/restormer_fp16_v1.zip
ok=true
BUILD SUCCESSFUL
```

### Верификация загруженных моделей
```bash
$ ls -lh app/src/main/assets/models/models/
total 52M
-rw-rw-r-- 1 engine engine  51M Nov 11 18:20 restormer_fp16.bin
-rw-rw-r-- 1 engine engine 702K Nov 11 18:20 restormer_fp16.param
-rw-rw-r-- 1 engine engine  42K Nov 11 18:09 zerodcepp_fp16.bin
-rw-rw-r-- 1 engine engine 9.9K Nov 11 18:09 zerodcepp_fp16.param
```

## Критерии приёмки

- ✅ `:fetchModels` скачивает models-v1 с `github.com/artkoder/...` (200 OK, не 404)
- ✅ `models.lock.json` содержит `"repository": "artkoder/kotopogoda-uploader-android"`
- ✅ `build.gradle.kts` имеет усиленный fallback с GITHUB_REPOSITORY
- ✅ CI workflow build имеет env MODELS_REPOSITORY
- ✅ `restormer_fp32` присутствует в models.lock.json с precision=fp32 и enabled=false, поэтому APK по-прежнему тянет только FP16 веса
- ✅ Сборка APK проходит без FileNotFoundException

## Заключение

**Проблема была решена в коммите `fdaf51b`** ("Fix model repository owner, enhance fallback logic and CI robustness").

Все компоненты настроены правильно:
- Repository в lock-файле указывает на artkoder
- Fallback-логика учитывает env переменные MODELS_REPOSITORY и GITHUB_REPOSITORY
- CI workflow корректно передаёт MODELS_REPOSITORY при сборке
- Модели успешно загружаются с правильного репозитория

Дополнительных изменений не требуется.
