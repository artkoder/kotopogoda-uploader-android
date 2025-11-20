# Enhancement Feature Testing Documentation

Данный документ описывает структуру и покрытие тестов для функциональности enhancement (улучшение фотографий).

## Обзор

Enhancement функционал покрывается четырьмя типами тестов:
1. **Юнит-тесты** для state machine и бизнес-логики
2. **Compose UI тесты** для интерфейса
3. **Instrumentation тесты** для JNI bridge и inference на устройстве
4. **Shader fallback тесты** для CPU-based операций

## Структура тестов

### Unit Tests (`src/test`)

#### ViewerViewModelEnhancementStateTest.kt
Тесты state machine контроллера enhancement:
- ✅ Переходы состояний: Idle → ComputingPreview → Ready
- ✅ Отмена вычислений при смене фото
- ✅ Обработка сброса слайдера (reset handling)
- ✅ Кеширование full-resolution результатов
- ✅ Распространение телеметрии из engine в state
- ✅ Обновления прогресса по тайлам (tile progress)

#### EnhanceEngineTest.kt
Расширенные тесты для EnhanceEngine:
- ✅ Вычисление метрик изображения (brightness, sharpness, noise)
- ✅ Профили enhancement для разных strength значений
- ✅ Zero-DCE activation для low-light изображений
- ✅ Restormer деноизинг для шумных изображений
- ✅ Телеметрия моделей (backend, checksums)
- ✅ Tile progress callbacks
- ✅ Seamless blending metrics (seam detection)
- ✅ Тайминги всех pipeline стадий
- ✅ Delegate fallback (GPU → CPU)

#### EnhanceEngineShaderFallbackTest.kt
Тесты CPU fallback path:
- ✅ CPU blending когда GPU недоступен
- ✅ Alpha composite корректность
- ✅ Boundary conditions (alpha=0, alpha=1)
- ✅ Hann window для smooth seams
- ✅ Sharpen operation сохраняет размеры
- ✅ Vibrance/saturation clamping
- ✅ Delegate reporting в телеметрии

#### EnhancementSettingsTest.kt
Тесты настроек enhancement:
- ✅ Персистентность настроек
- ✅ Preview vs Full quality режимы
- ✅ UI toggles и их влияние
- ✅ Выбор профилей контроллером
- ✅ Влияние quality на параметры (tile size, iterations)
- ✅ Re-computation при изменении настроек

### Compose UI Tests (`src/androidTest`)

#### ViewerScreenEnhancementTest.kt
UI тесты для enhancement компонентов:
- ✅ Отображение значения слайдера (strength label)
- ✅ Регулировка слайдера (swipe gestures)
- ✅ Loader overlay во время обработки
- ✅ Progress indicator с tile progress
- ✅ Отображение результата enhancement
- ✅ Состояние слайдера (enabled/disabled)

Используемые test tags:
- `enhancement_slider` - основной слайдер
- `enhancement_strength_label` - метка со значением %
- `enhancement_loader` - circular progress
- `enhancement_progress` - linear progress bar
- `enhancement_progress_text` - текст прогресса
- `enhancement_status_text` - текст статуса

#### ViewerScreenTest.kt
Общие UI тесты viewer экрана (существующие тесты обновлены для совместимости)

### Instrumentation Tests (`src/androidTest`)

#### EnhanceEngineInstrumentationTest.kt
End-to-end тесты на реальном устройстве/эмуляторе:
- ✅ Загрузка и декодирование sample photo assets
- ✅ Preview inference execution
- ✅ Full-resolution inference с проверкой размеров
- ✅ Телеметрия (backend, Vulkan support, timings)
- ✅ Delegate fallback behavior
- ✅ Тайлинг для больших изображений
- ✅ Консистентность метрик между запусками
- ✅ Определение характеристик изображения (dark/bright)

## Запуск тестов

### Все unit тесты
```bash
./gradlew :feature:viewer:testDebugUnitTest
```

### Конкретная группа тестов
```bash
./gradlew :feature:viewer:testDebugUnitTest --tests "*.EnhanceEngineTest"
./gradlew :feature:viewer:testDebugUnitTest --tests "*.ViewerViewModelEnhancementStateTest"
./gradlew :feature:viewer:testDebugUnitTest --tests "*.EnhanceEngineShaderFallbackTest"
./gradlew :feature:viewer:testDebugUnitTest --tests "*.EnhancementSettingsTest"
```

### Compose UI тесты
```bash
./gradlew :feature:viewer:connectedDebugAndroidTest --tests "*.ViewerScreenEnhancementTest"
```

### Instrumentation тесты
```bash
./gradlew :feature:viewer:connectedDebugAndroidTest --tests "*.EnhanceEngineInstrumentationTest"
```

## Покрытие

### State Machine Transitions
- [x] Idle → ComputingPreview transition
- [x] ComputingPreview → Ready transition
- [x] Cancellation on photo change
- [x] Slider reset handling
- [x] Result caching

### Telemetry
- [x] Metrics propagation (lMean, pDark, bSharpness, nNoise)
- [x] Profile propagation (kDce, restormerMix, sharpenAmount, etc.)
- [x] Pipeline telemetry (tileCount, overlap, seamMetrics)
- [x] Models telemetry (backend, checksum, checksumOk)
- [x] Timings (decode, enhance stages, encode)
- [x] Delegate type (GPU/CPU)

### UI Components
- [x] Slider value display
- [x] Slider interaction (onChange, onChangeFinished)
- [x] Loader overlay (CircularProgressIndicator)
- [x] Progress indicator (LinearProgressIndicator)
- [x] Progress text updates
- [x] Status text updates

### JNI Bridge
- [x] Sample asset loading
- [x] Preview inference execution
- [x] Full-res inference execution
- [x] Output dimensions verification
- [x] Output files creation
- [x] Backend telemetry (TFLite/NCNN)
- [x] Delegate selection and fallback

### Shader Fallback
- [x] CPU blending when GPU unavailable
- [x] Alpha composite correctness
- [x] Hann window smoothing
- [x] Value clamping [0, 255]
- [x] Dimension preservation
- [x] Telemetry accuracy

### Settings Integration
- [x] Persistence across sessions
- [x] Preview/Full quality modes
- [x] UI toggles
- [x] Profile selection
- [x] Parameter adjustment (tile size, iterations)
- [x] Re-computation triggers

## Известные ограничения

1. **Micro-benchmark**: Опциональные timing assertions не реализованы. Для детальной оценки производительности рекомендуется использовать Android Jetpack Benchmark library.

2. **Real GPU testing**: Instrumentation тесты запускаются с CPU backend. Для тестирования GPU delegate требуется реальное устройство с TFLite GPU delegate или NCNN Vulkan support.

3. **AGSL shader testing**: Shader fallback тесты проверяют CPU path логику. Реальное тестирование Android Graphics Shading Language (AGSL) требует Android 13+ и специального environment setup.

## Рекомендации

### При добавлении новых features
1. Добавить unit тесты для новой логики в `EnhanceEngineTest.kt`
2. Обновить state machine тесты если меняется flow
3. Добавить UI тесты если добавляются новые компоненты
4. Использовать существующие test tags для Compose тестов

### При изменении телеметрии
1. Обновить assertions в `ViewerViewModelEnhancementStateTest.kt`
2. Проверить что все поля корректно propagate
3. Обновить logging tests в `ViewerViewModelEnhanceLogTest.kt`

### При оптимизации производительности
1. Запустить instrumentation тесты на целевых устройствах
2. Сравнить timings до и после изменений
3. Проверить что tile progress callbacks не влияют на performance

## Troubleshooting

### Тесты падают с timeout
Увеличить timeout в тестах или использовать `runTest` с `timeout` parameter.

### Instrumentation тесты не могут создать файлы
Проверить permissions в AndroidManifest.xml и использовать `context.cacheDir` для временных файлов.

### UI тесты не находят компоненты
Проверить что используются правильные test tags и что компоненты actually composed.

### MockK ошибки в unit тестах
Убедиться что `relaxed = true` используется для mock dependencies и что все coroutine dispatchers заменены на `TestDispatcher`.

## QA: принудительный CPU backend

Энхансер теперь всегда стартует в CPU-only режиме. В телеметрии (`native_controller_init`, `native_delegate_status`, preview/full events) по умолчанию будут `delegate_plan=cpu`, `delegate_available=cpu_only`, `delegate_used=cpu`, `force_cpu=true`, `force_cpu_reason="cpu_only"` независимо от устройства.

Переключатель **«Принудительно CPU backend»** в dev-настройках остаётся доступным, но влияет только на логи/телеметрию (через `NativeEnhanceController.setForceCpuOverride` и DiagnosticContextProvider). Используйте его, чтобы пометить конкретные QA-сессии или автотесты, не ожидая изменения фактического бэкенда.

Переменная окружения `ENHANCE_FORCE_CPU` сохранена для совместимости, но на работу приложения более не влияет — CPU режим уже включён на Kotlin-стороне. Для экспериментов с GPU-путём используйте прямую инициализацию `NativeEnhanceController.InitParams(forceCpu = false)` в тестах.
