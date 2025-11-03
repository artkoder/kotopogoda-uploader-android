# Test Utilities

Этот модуль предоставляет общие утилиты для unit-тестов.

## MainDispatcherRule

JUnit4 правило для автоматической установки `Dispatchers.Main` в `StandardTestDispatcher`.

### Использование

```kotlin
import com.kotopogoda.uploader.core.logging.test.MainDispatcherRule
import org.junit.Rule

class MyViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()
    
    @Test
    fun myTest() = runTest {
        // Dispatchers.Main уже настроен автоматически
    }
}
```

## RobolectricTestBase

Базовый класс для Robolectric тестов с предустановленными настройками:
- `@LooperMode(LooperMode.Mode.PAUSED)` для детерминистических тестов
- SDK 34 по умолчанию
- Отключенное логирование в stdout

### Использование

```kotlin
import com.kotopogoda.uploader.core.logging.test.RobolectricTestBase

class MyRobolectricTest : RobolectricTestBase() {
    @Test
    fun myTest() {
        val context = getContext()
        // Тест с Android API
    }
}
```

## Глобальные настройки тестов

В корневом `build.gradle.kts` настроены глобальные параметры для всех JVM unit-тестов:

- `maxParallelForks = 1` - один тест за раз для стабильности
- `forkEvery = 50` - новый процесс каждые 50 тестов
- `maxHeapSize = "1g"` - ограничение heap
- `-XX:+HeapDumpOnOutOfMemoryError` - heap dump при OOM
- `-Dkotlinx.coroutines.scheduler.max.pool.size=2` - ограничение потоков coroutines
- `-Dkotlinx.coroutines.debug=off` - отключение debug режима

Эти настройки помогают предотвратить:
- Зависания из-за неконтролируемой конкурентности
- OOM из-за утечек памяти
- Недетерминистичные тесты из-за использования глобального Dispatchers.Default
