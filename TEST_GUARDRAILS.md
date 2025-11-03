# Test Guardrails Implementation

## Обзор

Внедрены глобальные guardrails для JVM unit-тестов для предотвращения зависаний, OOM и недетерминированных тестов.

## Изменения

### 1. Глобальная конфигурация тестов (build.gradle.kts)

Все JVM unit-тесты теперь выполняются с следующими ограничениями:

```kotlin
tasks.withType<Test>().configureEach {
    maxParallelForks = 1        // Один процесс тестов за раз
    forkEvery = 50               // Новый процесс каждые 50 тестов
    failFast = true              // Остановка при первой ошибке
    maxHeapSize = "1g"           // Максимум 1GB heap
}
```

**JVM аргументы:**
- `-XX:MaxRAMPercentage=70` - ограничение использования RAM
- `-XX:+HeapDumpOnOutOfMemoryError` - создание heap dump при OOM
- `-XX:HeapDumpPath=${project.buildDir}/test-heap-dumps/` - путь для heap dumps
- `-Dkotlinx.coroutines.scheduler.max.pool.size=2` - максимум 2 потока в coroutines scheduler
- `-Dkotlinx.coroutines.debug=off` - отключение debug режима для производительности

### 2. Общие утилиты для тестов (core:logging)

#### MainDispatcherRule

JUnit4 правило для автоматической установки `Dispatchers.Main` в `StandardTestDispatcher`:

```kotlin
@get:Rule
val mainDispatcherRule = MainDispatcherRule()

@Test
fun myTest() = runTest {
    // Dispatchers.Main уже настроен
}
```

#### RobolectricTestBase

Базовый класс для Robolectric тестов с предустановленными настройками:
- `@LooperMode(LooperMode.Mode.PAUSED)` - детерминистические Looper'ы
- SDK 34 по умолчанию
- Отключенное логирование для уменьшения шума

```kotlin
class MyTest : RobolectricTestBase() {
    @Test
    fun myTest() {
        val context = getContext()
        // Тест с Android API
    }
}
```

### 3. Миграция существующих тестов

Удалены дублирующиеся `MainDispatcherRule` из:
- `feature:queue`
- `feature:onboarding`
- `feature:status`
- `feature:viewer` (частично)

Все тесты теперь используют общий `MainDispatcherRule` из `core:logging`.

## Преимущества

1. **Предотвращение зависаний**: Ограничение параллелизма и потоков coroutines предотвращает runaway concurrency
2. **Диагностика OOM**: Heap dumps создаются автоматически при OOM
3. **Детерминистичные тесты**: Использование `StandardTestDispatcher` вместо глобального `Dispatchers.Default`
4. **Предотвращение утечек памяти**: `forkEvery=50` создаёт новый процесс регулярно
5. **Быстрая обратная связь**: `failFast=true` останавливает выполнение при первой ошибке

## Использование

### Для новых тестов с coroutines:

```kotlin
import com.kotopogoda.uploader.core.logging.test.MainDispatcherRule
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

class MyTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()
    
    @Test
    fun myTest() = runTest {
        // Тест
    }
}
```

### Для новых Robolectric тестов:

```kotlin
import com.kotopogoda.uploader.core.logging.test.RobolectricTestBase
import org.junit.Test

class MyTest : RobolectricTestBase() {
    @Test
    fun myTest() {
        val context = getContext()
        // Тест с Android API
    }
}
```

## Heap Dump при OOM

При возникновении OOM в тестах, heap dump будет создан в:
```
{module}/build/test-heap-dumps/java_pid{pid}.hprof
```

Для анализа используйте Android Studio Memory Profiler или Eclipse MAT.

## Дополнительная информация

См. также:
- `core/logging/TEST_UTILITIES.md` - подробная документация по тестовым утилитам
- `build.gradle.kts` - глобальная конфигурация тестов
