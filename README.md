# kotopogoda-uploader-android

Android-приложение для отбора и безопасной отправки фотографий на сервер (fly.io) в пайплайн "assets".

## Минимальные требования
- Android 15 (minSdk 35)
- Язык: Kotlin, UI: Jetpack Compose (Material 3)

## Статус
Инициализация репозитория. Дальше Codex создаст каркас проекта.

## Разрешения
- Чтение медиаконтента (READ_MEDIA_IMAGES или READ_EXTERNAL_STORAGE для Android 12L и ниже) — требуется для просмотра и отправки фотографий.
- Камера — для сканирования QR-кода при привязке устройства.

## Сборка
Локальная сборка debug-APK:

```sh
gradle assembleDebug
```

## Документация
- Архитектура (черновик): app + core:data + core:network + feature:viewer + feature:onboarding
- Безопасность: привязка устройства (QR), HMAC-подпись запросов
- Контракт API: vendored через подмодуль `api/contract` (версия v1.4.1), официальная документация: https://artkoder.github.io/kotopogoda-api-contract/
