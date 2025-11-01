# Native NCNN Integration

## Обзор

Приложение использует нативный модуль на C++ для ускорения обработки изображений с помощью NCNN и Vulkan.

## Компоненты

### Нативный модуль (app/src/main/cpp/)

- **native_enhance_jni.cpp** - JNI обертка для взаимодействия с Kotlin
- **ncnn_engine.cpp** - Основной движок, управляющий моделями NCNN
- **restormer_backend.cpp** - Обработка Restormer с тайлингом
- **zerodce_backend.cpp** - Обработка Zero-DCE++ с регулируемой силой
- **tile_processor.cpp** - Тайловая обработка больших изображений
- **hann_window.cpp** - Оконная функция для сглаживания швов
- **sha256_verifier.cpp** - Верификация контрольных сумм моделей

### Kotlin интеграция

- **NativeEnhanceController** - Контроллер для управления нативным движком
- **NativeEnhanceAdapter** - Адаптер для высокоуровневого API

## Сборка

### Требования

1. Android NDK 26.1.10909125+
2. CMake 3.22.1+
3. NCNN библиотеки (скачиваются автоматически)

### Загрузка NCNN

Перед первой сборкой выполните:

```bash
./scripts/download_ncnn.sh
```

### Сборка через Gradle

```bash
./gradlew :app:assembleDebug
```

## Архитектура

### Тайловая обработка

Для изображений больше 512x512 используется тайловая обработка:

1. Изображение разбивается на тайлы 512x512 с перекрытием 16px
2. Каждый тайл обрабатывается независимо
3. Результаты сшиваются с использованием окна Ханна для сглаживания швов

```
┌─────────┬─────────┐
│  Tile 1 │ Tile 2  │  Перекрытие 16px
│         │         │
├─────────┼─────────┤
│  Tile 3 │ Tile 4  │
└─────────┴─────────┘
```

### Профили качества

- **BALANCED** - Только Zero-DCE++ (быстрее)
- **QUALITY** - Restormer → Zero-DCE++ (выше качество)

### Vulkan

- Автоматическое определение доступности Vulkan
- Fallback на CPU с ARM NEON оптимизациями
- Конфигурируемое количество потоков (4-8)

## API

### Инициализация

```kotlin
val controller = NativeEnhanceController()
controller.initialize(InitParams(
    assetManager = context.assets,
    modelsDir = File(context.filesDir, "models"),
    zeroDceChecksum = "...",
    restormerChecksum = "...",
    previewProfile = PreviewProfile.QUALITY
))
```

### Preview обработка

```kotlin
val result = controller.runPreview(
    sourceBitmap = bitmap,
    strength = 0.8f,
    onProgress = { info -> 
        println("Progress: ${info.progress}")
    }
)
```

### Full обработка

```kotlin
val result = controller.runFull(
    sourceBitmap = bitmap,
    strength = 0.8f,
    outputBitmap = outputBitmap,
    onProgress = { info -> 
        println("Progress: ${info.progress}")
    }
)
```

### Отмена

```kotlin
controller.cancel()
```

### Освобождение ресурсов

```kotlin
controller.release()
```

## Telemetry

Каждая операция возвращает метрики:

```kotlin
data class TelemetryData(
    val timingMs: Long,          // Время выполнения
    val usedVulkan: Boolean,     // Использовался ли Vulkan
    val peakMemoryKb: Long,      // Пиковое использование памяти
    val cancelled: Boolean       // Была ли отменена операция
)
```

## Отладка

### Логи

Все нативные компоненты пишут логи в Android logcat:

```bash
adb logcat -s NativeEnhanceJNI:* NcnnEngine:* RestormerBackend:* ZeroDceBackend:* TileProcessor:*
```

### Проверка Vulkan

```bash
adb logcat -s NcnnEngine:I | grep "Vulkan"
```

### Отслеживание тайлов

```bash
adb logcat -s TileProcessor:I
```

## Производительность

### Оптимизации

- **ARM NEON** - Векторные инструкции для ARM64
- **Vulkan Compute** - GPU ускорение на поддерживаемых устройствах
- **FP16** - Использование половинной точности для storage/packed
- **Многопоточность** - 4-8 потоков в зависимости от операции

### Типичные показатели

- Preview (720p): 100-300ms (Vulkan), 300-800ms (CPU)
- Full (2048x1536): 1000-3000ms (Vulkan), 3000-8000ms (CPU)

## Troubleshooting

### Библиотека не загружается

```
java.lang.UnsatisfiedLinkError: dlopen failed: library "libkotopogoda_enhance.so" not found
```

**Решение**: Убедитесь, что библиотека собрана для нужной архитектуры (arm64-v8a или x86_64)

### Несоответствие контрольных сумм

```
Несоответствие контрольной суммы для zerodce.bin
```

**Решение**: Проверьте, что модели соответствуют ожидаемым версиям. Логируется только один раз за процесс.

### Vulkan недоступен

```
Vulkan недоступен, используется CPU
```

**Решение**: Нормальное поведение на устройствах без Vulkan или эмуляторах. Используется CPU fallback.

### Ошибки при тайловой обработке

```
Ошибка обработки тайла 5
```

**Решение**: Проверьте доступную память. Возможно, нужно уменьшить размер тайлов или увеличить overlap.

## CI/CD

### GitHub Actions

Нативный модуль собирается автоматически на CI. Убедитесь, что workflow включает:

```yaml
- name: Download NCNN
  run: ./scripts/download_ncnn.sh

- name: Build APK
  run: ./gradlew assembleDebug
```

### Pre-commit хуки

Проверка нативного кода:

```bash
# Статический анализ
clang-tidy app/src/main/cpp/*.cpp

# Форматирование
clang-format -i app/src/main/cpp/*.cpp
```
