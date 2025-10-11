# kotopogoda-uploader-android

Android-приложение для отбора и безопасной отправки фотографий на сервер (fly.io) в пайплайн "assets".

## Минимальные требования
- Android 15 (minSdk 35)
- Язык: Kotlin, UI: Jetpack Compose (Material 3)

## Статус
Инициализация репозитория. Дальше Codex создаст каркас проекта.

## Сборка
Локальная сборка debug-APK:

```sh
gradle assembleDebug
```

## Документация
- Архитектура (черновик): app + core:data + core:network + feature:viewer + feature:onboarding
- Безопасность: привязка устройства (QR), HMAC-подпись запросов
