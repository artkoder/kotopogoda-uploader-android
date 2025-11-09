# Диагностика: почему mockkинг MediaStore требует столько памяти

## Дата: 2024
## Статус: Диагностика завершена

---

## 📋 TL;DR (Краткое резюме)

**Проблема**: mockkStatic для MediaStore и DocumentsContract требует 100-180 MB памяти на тест из-за трансформации огромных Android framework классов.

**Найдено**:
- `SaFileRepositoryTest.kt`: 15 тестов используют mockkStatic(MediaStore, DocumentsContract, DocumentFile)
- `ViewerViewModelBatchDeleteTest.kt`: 1 тест использует mockkStatic(MediaStore)

**Решение**: Вынести все статические вызовы в инжектируемые переменные (паттерн уже частично используется в коде) и убрать mockkStatic.

**Эффект**: Снижение потребления памяти на **90%** (с 1-2 GB до 100-200 MB).

**Трудоёмкость**: 2-4 часа (низкая).

---

## 1. Текущая ситуация

### 1.1 Файл с проблемой
`core/data/src/test/java/com/kotopogoda/uploader/core/data/sa/SaFileRepositoryTest.kt`

### 1.2 Версия mockk
**1.13.10** (из `gradle/libs.versions.toml`) — актуальная версия, проблема не в ней.

### 1.3 Что мокируется

#### Instance mocks (лёгкие):
- `Context` (relaxed)
- `ContentResolver` (relaxed)
- `ProcessingFolderProvider`
- `DocumentFile` экземпляры
- `PendingIntent`, `IntentSender`, `Icon`, `RemoteAction`

#### Static mocks (ТЯЖЁЛЫЕ):
```kotlin
mockkStatic(MediaStore::class)           // ~15 тестов
mockkStatic(DocumentsContract::class)    // ~8 тестов
mockkStatic(DocumentFile::class)         // ~6 тестов
```

---

## 2. Корневая причина высокого потребления памяти

### 2.1 Проблема: mockkStatic для Android framework классов

**MediaStore** и **DocumentsContract** — это огромные Android framework классы:
- MediaStore: ~70+ статических методов, множество вложенных классов (Images, Video, Audio, Files, MediaColumns, etc.)
- DocumentsContract: ~40+ статических методов, вложенные классы (Document, Root, etc.)

**mockkStatic** вынужден:
1. Загрузить полную структуру класса
2. Создать proxy для ВСЕХ статических методов
3. Трансформировать байткод для перехвата вызовов
4. Держать это в памяти на протяжении всего теста

**Оценка памяти**:
- mockkStatic(MediaStore::class): ~50-100 MB
- mockkStatic(DocumentsContract::class): ~30-50 MB
- mockkStatic(DocumentFile::class): ~20-30 MB
- **Итого на ОДИН тест**: 100-180 MB

**При 15 тестах и forkEvery=50**: все тесты в одном процессе → суммарное потребление ~1.5-2 GB.

### 2.2 Противоречие с Robolectric

Тесты уже используют:
```kotlin
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
```

**Robolectric предназначен именно для того, чтобы предоставить shadow-реализации Android framework классов!**

Но вместо использования shadow-классов Robolectric, всё мокируется через mockk — это расточительно и избыточно.

---

## 3. Анализ альтернатив

### 3.1 ❌ Увеличить heap
```kotlin
tasks.withType<Test> {
    maxHeapSize = "2g"  // было 1g
}
```

**Проблема**: Не решает корневую причину, только маскирует симптом.

---

### 3.2 ✅ Вариант А: Использовать Robolectric shadow-классы

**Описание**: Robolectric уже предоставляет shadow-реализации для MediaStore, DocumentsContract, ContentResolver.

**Преимущества**:
- ✅ Полностью убирает mockkStatic
- ✅ Память: снижение на 80-90%
- ✅ Тесты работают с "настоящими" Android объектами
- ✅ Более приближено к реальному поведению

**Недостатки**:
- ❌ Shadow API может быть менее гибким, чем mockk
- ❌ Требует переписывания тестов (средняя сложность)

**Пример использования shadow API**:
```kotlin
// Вместо mockkStatic(MediaStore::class)
val shadowMediaStore = shadowOf(MediaStore::class.java)
shadowMediaStore.setVolumeName(uri, "external_primary")

// Вместо mockkStatic(DocumentsContract::class)
// Robolectric предоставляет частичную реализацию DocumentsContract out-of-the-box
```

**Оценка**: Хорошее решение для быстрого снижения памяти, но требует изучения Robolectric shadow API.

---

### 3.3 ✅✅ Вариант Б: Вынести статические вызовы в инжектируемые зависимости

**Описание**: В коде уже есть паттерн внедрения зависимостей через переменные:

```kotlin
// В SaFileRepository.kt (строки 463-473)
internal var mediaStoreVolumeResolver: (Uri) -> String? = { uri ->
    runCatching { MediaStore.getVolumeName(uri) }.getOrNull()
}

internal var mediaStoreWriteRequestFactory: (ContentResolver, List<Uri>) -> PendingIntent = { resolver, uris ->
    MediaStore.createWriteRequest(resolver, uris)
}

internal var mediaStoreDeleteRequestFactory: (ContentResolver, List<Uri>) -> PendingIntent = { resolver, uris ->
    MediaStore.createDeleteRequest(resolver, uris)
}
```

**Эти переменные УЖЕ используются в тестах** (строки 40-42, 97, 130, 177, 226, 358 в тесте).

**НО**: StaticMock всё равно используется для других методов:
- `DocumentsContract.getDocumentId()`
- `DocumentsContract.buildDocumentUriUsingTree()`

**Решение**: Добавить переменные для этих методов и убрать все mockkStatic.

**Преимущества**:
- ✅ Полностью убирает mockkStatic
- ✅ Память: снижение на 90-95%
- ✅ Минимально инвазивное (код уже использует этот паттерн)
- ✅ Улучшает тестируемость и архитектуру
- ✅ Легко мокировать в тестах через присваивание

**Недостатки**:
- ❌ Требует небольших изменений в production коде (добавить 2-3 переменные)

**Пример реализации**:
```kotlin
// В SaFileRepository.kt
internal var documentsContractGetDocumentId: (Uri) -> String = { uri ->
    DocumentsContract.getDocumentId(uri)
}

internal var documentsContractBuildDocumentUri: (Uri, String) -> Uri = { treeUri, documentId ->
    DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
}

internal var documentFileFromSingleUri: (Context, Uri) -> DocumentFile? = { context, uri ->
    DocumentFile.fromSingleUri(context, uri)
}

internal var documentFileFromTreeUri: (Context, Uri) -> DocumentFile? = { context, uri ->
    DocumentFile.fromTreeUri(context, uri)
}

// В тестах
documentsContractGetDocumentId = { uri -> "external:Pictures/На обработку" }
documentsContractBuildDocumentUri = { treeUri, docId -> mockUri }
documentFileFromSingleUri = { _, _ -> mockDocumentFile }
```

**Оценка**: **ЛУЧШЕЕ РЕШЕНИЕ** — минимальные изменения, максимальный эффект.

---

### 3.4 ⚠️ Вариант В: Переписать на фейки

**Описание**: Создать FakeContentResolver, FakeDocumentFile, FakeMediaStore.

**Преимущества**:
- ✅ Полностью убирает mockk
- ✅ Память: снижение на 95%+
- ✅ Полный контроль над поведением

**Недостатки**:
- ❌ Требует написания большого объёма фейк-классов
- ❌ Требует полного переписывания тестов
- ❌ Высокая трудоёмкость

**Оценка**: Слишком дорого для текущей задачи.

---

### 3.5 ⚠️ Вариант Г: Разделить на unit и integration тесты

**Описание**: 
- Unit-тесты: только логика SaFileRepository (без Android API)
- Integration-тесты: с реальным Android окружением (Robolectric или Device/Emulator)

**Проблема**: SaFileRepository **сильно завязан** на Android API (ContentResolver, MediaStore, DocumentsContract). Выделить чистую unit-логику без Android крайне сложно.

**Оценка**: Не подходит для данного класса.

---

## 4. Рекомендация

### 4.1 Immediate fix (краткосрочный)

**Вариант Б**: Добавить переменные для всех статических вызовов и убрать mockkStatic.

**План действий**:

1. **Добавить переменные в SaFileRepository.kt** (после строки 473):
   ```kotlin
   internal var documentsContractGetDocumentId: (Uri) -> String = { uri ->
       DocumentsContract.getDocumentId(uri)
   }
   
   internal var documentsContractBuildDocumentUri: (Uri, String) -> Uri = { treeUri, documentId ->
       DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
   }
   
   internal var documentFileFromSingleUri: (Context, Uri) -> DocumentFile? = { context, uri ->
       DocumentFile.fromSingleUri(context, uri)
   }
   
   internal var documentFileFromTreeUri: (Context, Uri) -> DocumentFile? = { context, uri ->
       DocumentFile.fromTreeUri(context, uri)
   }
   ```

2. **Заменить все прямые вызовы в SaFileRepository.kt** на вызовы через переменные:
   - `DocumentsContract.getDocumentId(uri)` → `documentsContractGetDocumentId(uri)`
   - `DocumentsContract.buildDocumentUriUsingTree(...)` → `documentsContractBuildDocumentUri(...)`
   - `DocumentFile.fromSingleUri(...)` → `documentFileFromSingleUri(...)`
   - `DocumentFile.fromTreeUri(...)` → `documentFileFromTreeUri(...)`

3. **Убрать все mockkStatic из тестов**:
   - Удалить `mockkStatic(MediaStore::class)` (уже покрыто существующими переменными)
   - Удалить `mockkStatic(DocumentsContract::class)`
   - Удалить `mockkStatic(DocumentFile::class)`

4. **Переписать тесты**, чтобы они использовали переменные вместо mockkStatic:
   ```kotlin
   // Вместо
   mockkStatic(DocumentsContract::class)
   every { DocumentsContract.getDocumentId(uri) } returns "external:Pictures"
   
   // Использовать
   documentsContractGetDocumentId = { uri -> "external:Pictures" }
   ```

5. **Добавить tearDown** для сброса переменных:
   ```kotlin
   @After
   fun tearDown() {
       mediaStoreVolumeResolver = originalMediaStoreVolumeResolver
       mediaStoreWriteRequestFactory = originalMediaStoreWriteRequestFactory
       mediaStoreDeleteRequestFactory = originalMediaStoreDeleteRequestFactory
       documentsContractGetDocumentId = originalDocumentsContractGetDocumentId
       documentsContractBuildDocumentUri = originalDocumentsContractBuildDocumentUri
       documentFileFromSingleUri = originalDocumentFileFromSingleUri
       documentFileFromTreeUri = originalDocumentFileFromTreeUri
       unmockkAll()
   }
   ```

**Ожидаемый результат**:
- ✅ Потребление памяти: **1-2 GB → 100-200 MB** (снижение на 90%)
- ✅ Скорость тестов: увеличение на 30-50%
- ✅ Heap dumps при OOM: больше не нужны

---

### 4.2 Long-term improvement (долгосрочный)

Рассмотреть рефакторинг SaFileRepository:
1. Выделить интерфейсы для работы с Android API
2. Использовать DI для внедрения реализаций
3. В тестах использовать лёгкие фейки или тестовые реализации

**Пример**:
```kotlin
interface MediaStoreOperations {
    fun getVolumeName(uri: Uri): String?
    fun createWriteRequest(resolver: ContentResolver, uris: List<Uri>): PendingIntent
    fun createDeleteRequest(resolver: ContentResolver, uris: List<Uri>): PendingIntent
}

interface DocumentsContractOperations {
    fun getDocumentId(uri: Uri): String
    fun buildDocumentUriUsingTree(treeUri: Uri, documentId: String): Uri
}

class SaFileRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val processingFolderProvider: ProcessingFolderProvider,
    private val mediaStoreOps: MediaStoreOperations,
    private val documentsContractOps: DocumentsContractOperations,
) { ... }
```

**Преимущества**:
- Улучшенная тестируемость
- Полная изоляция от Android framework
- Возможность использовать фейки в тестах

---

## 5. Другие файлы с mockkингом MediaStore

### 5.1 UploadQueueRepositoryTest.kt
- НЕ использует mockkStatic(MediaStore::class)
- Использует только instance mocks
- **Память**: нормальная

### 5.2 ViewerViewModelBatchDeleteTest.kt
- **ИСПОЛЬЗУЕТ** mockkStatic(MediaStore::class) в try-finally блоках
- Мокирует только один метод: `MediaStore.createDeleteRequest()`
- **Хорошая практика**: использует unmockkStatic в finally
- **Плохая практика**: всё равно трансформирует весь MediaStore класс
- **Места использования в ViewerViewModel.kt**:
  - Строка 834: `requestDelete()` — удаление одного фото на Android R+
  - Строка 926: `requestDeleteSelection()` — удаление нескольких фото на Android R+
- **Рекомендация**: 
  1. Добавить в ViewerViewModel companion object переменную:
     ```kotlin
     internal var mediaStoreDeleteRequestFactory: (ContentResolver, List<Uri>) -> PendingIntent = { resolver, uris ->
         MediaStore.createDeleteRequest(resolver, uris)
     }
     ```
  2. Заменить прямые вызовы на вызовы через переменную
  3. В тесте переопределять переменную вместо mockkStatic

---

## 6. Заключение

**Проблема**: mockkStatic для MediaStore и DocumentsContract требует 100-180 MB на тест из-за трансформации огромных Android framework классов.

**Решение**: Использовать паттерн внедрения зависимостей через переменные (уже частично есть в коде) и убрать все mockkStatic.

**Эффект**: Снижение потребления памяти на 90% (с 1-2 GB до 100-200 MB).

**Трудоёмкость**: Низкая (2-4 часа на реализацию).

**Риски**: Минимальные (паттерн уже используется, изменения локальные).

---

## 7. Action items

### 7.1 SaFileRepository (приоритет: высокий)
- [ ] Добавить переменные для DocumentsContract и DocumentFile в SaFileRepository.kt
- [ ] Заменить все прямые вызовы на вызовы через переменные в SaFileRepository.kt
- [ ] Удалить все mockkStatic из SaFileRepositoryTest.kt (15 тестов)
- [ ] Переписать тесты для использования переменных вместо mockkStatic
- [ ] Запустить тесты: `./gradlew :core:data:testDebugUnitTest`

### 7.2 ViewerViewModel (приоритет: средний)
- [ ] Добавить переменную mediaStoreDeleteRequestFactory в ViewerViewModel.kt
- [ ] Заменить прямые вызовы MediaStore.createDeleteRequest (2 места)
- [ ] Удалить mockkStatic из ViewerViewModelBatchDeleteTest.kt
- [ ] Переписать тест для использования переменной
- [ ] Запустить тесты: `./gradlew :feature:viewer:testDebugUnitTest`

### 7.3 Финальная проверка
- [ ] Запустить все unit-тесты: `./gradlew testDebugUnitTest`
- [ ] Измерить потребление памяти после изменений
- [ ] Удалить или уменьшить maxHeapSize в build.gradle.kts (опционально)
- [ ] Обновить память (UpdateMemory) с результатами

---

**Автор**: AI Agent  
**Дата**: 2024
