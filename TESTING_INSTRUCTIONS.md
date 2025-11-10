# Инструкции по тестированию логирования на реальном устройстве

## Быстрый старт

### 1. Сборка и установка

```bash
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### 2. Запуск приложения

```bash
adb shell am start -n com.kotopogoda.uploader/.MainActivity
```

### 3. Проверка файловых логов

```bash
# Список логов
adb shell "run-as com.kotopogoda.uploader ls -lh /data/data/com.kotopogoda.uploader/files/logs/"

# Просмотр главного лога
adb shell "run-as com.kotopogoda.uploader cat /data/data/com.kotopogoda.uploader/files/logs/app.log"

# Сохранение локально
adb shell "run-as com.kotopogoda.uploader cat /data/data/com.kotopogoda.uploader/files/logs/app.log" > app_logs.txt
```

### 4. Проверка logcat (для DEBUG билдов)

```bash
# Очистить буфер
adb logcat -c

# Фильтр по пакету
adb logcat | grep "com.kotopogoda.uploader"

# Фильтр по тегам
adb logcat | grep -E "WorkManager|NativeEnhance|app|Enhance"

# Сохранение всего logcat
adb logcat -d > logcat.txt
```

## Сценарии тестирования

### ✅ Холодный старт
- Очистить данные: `adb shell pm clear com.kotopogoda.uploader`
- Запустить приложение
- Проверить лог: `APP/START` / `application_create`

### ✅ Онбординг и привязка
- Пройти онбординг
- Отсканировать QR код
- Проверить сетевые логи (если httpLogging включён)

### ✅ Загрузка фото
- Выбрать фото из галереи
- Проверить логи работы с хранилищем
- Проверить логи очереди загрузки

### ✅ Ошибочный сценарий
- Отключить WiFi/мобильные данные
- Попытаться загрузить фото
- Проверить логи ошибок с полными стеками

### ✅ Изменение настроек
- Открыть настройки
- Изменить baseUrl
- Отключить/включить логирование
- Проверить: `CFG/STATE` / `settings`

### ✅ Фоновая работа
- Поставить файл в очередь
- Свернуть приложение (Home кнопка)
- Проверить WorkManager логи

## Что проверять в логах

### Обязательные элементы:
- ✅ Временные метки: `[2024-01-15 10:23:45.123]`
- ✅ Уровни: `[I]`, `[W]`, `[E]`, `[D]`
- ✅ Теги модулей: `WorkManager`, `app`, `NativeEnhance`
- ✅ Структурированные сообщения: `category=... action=...`
- ✅ Полные стеки при исключениях (не только сообщение)

### Примеры ожидаемых записей:

```
[2024-01-15 10:23:45.123][I] WorkManager: category=WORK/Factory action=app_init
[2024-01-15 10:23:45.234][I] app: category=APP/START action=application_create
[2024-01-15 10:23:45.345][I] app: category=CFG/STATE action=settings base_url=https://... app_logging=true http_logging=true
[2024-01-15 10:23:45.456][I] app: category=PERM/STATE action=notifications granted=true
```

## Решение проблем

### Логи не видны в файлах
- Проверить настройки: возможно, логирование отключено в UI
- Переустановить приложение (очистка данных)
- Проверить разрешения: `run-as` должен работать для debug билдов

### Логи не видны в logcat
- Это нормально для release билдов (только DebugTree в DEBUG)
- Используйте файловые логи вместо logcat

### Приложение крашится
- Собрать logcat с крэша: `adb logcat -d > crash.txt`
- Проверить файловые логи - там должен быть `FATAL/CRASH`

## Полный отчёт

Детальный анализ кода и рекомендации см. в файле:
**[LOGGING_VERIFICATION_REPORT.md](./LOGGING_VERIFICATION_REPORT.md)**

## Контрольный чеклист

- [ ] APK собран и установлен
- [ ] Приложение запускается без ошибок
- [ ] Логи записываются в `/data/data/.../files/logs/app.log`
- [ ] В DEBUG билде логи видны в logcat
- [ ] Временные метки, уровни и теги корректны
- [ ] Стеки исключений логируются полностью
- [ ] Ротация логов работает (5 файлов по 1MB)
- [ ] Настройки логирования применяются корректно
- [ ] Фоновые задачи логируются
- [ ] Крэши перехватываются и логируются

---

**Статус:** ✅ Логирование включено по-умолчанию и готово к тестированию
