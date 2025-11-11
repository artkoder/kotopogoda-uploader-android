import groovy.json.JsonSlurper
import java.math.BigDecimal
import java.net.HttpURLConnection
import java.net.URL
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.ZipInputStream
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.DEFAULT_BUFFER_SIZE
import kotlin.math.roundToLong
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.LocalState
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.kapt) apply false
    alias(libs.plugins.hilt) apply false
    id("org.openapi.generator") version "7.7.0" apply false
}

private fun String.toBuildConfigLiteral(): String {
    val escaped = this
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\r", "\\r")
        .replace("\n", "\\n")
    return "\"$escaped\""
}

val modelsLockFile = rootProject.file("models.lock.json")
if (!modelsLockFile.exists()) {
    throw IllegalStateException("Не найден models.lock.json в корне проекта")
}

val modelsLockContent = modelsLockFile.readText()
extra.set("modelsLockJson", modelsLockContent)
extra.set("modelsLockLiteral", modelsLockContent.toBuildConfigLiteral())

abstract class FetchModelsTask : DefaultTask() {

    @get:InputFile
    abstract val lockFile: RegularFileProperty

    @get:OutputDirectory
    abstract val targetDir: DirectoryProperty

    @get:LocalState
    abstract val buildDir: DirectoryProperty

    @get:Input
    @get:Optional
    abstract val overrideRepository: Property<String>

    init {
        group = "models"
        description = "Скачивает и распаковывает ML-модели согласно models.lock.json"
        lockFile.convention(project.layout.projectDirectory.file("models.lock.json"))
        targetDir.convention(project.layout.projectDirectory.dir("app/src/main/assets/models"))
        buildDir.convention(project.layout.buildDirectory.dir("models"))
    }

    @TaskAction
    fun fetch() {
        val file = lockFile.asFile.get()
        if (!file.exists()) {
            throw IllegalStateException("models.lock.json не найден: ${file.absolutePath}")
        }
        val json = JsonSlurper().parse(file) as? Map<*, *>
            ?: error("models.lock.json имеет неверный формат")
        val repoFromLock = (json["repository"] as? String)?.takeIf { it.isNotBlank() }
        val repository = overrideRepository.orNull?.takeIf { it.isNotBlank() }
            ?: System.getenv("MODELS_REPOSITORY")?.takeIf { it.isNotBlank() }
            ?: repoFromLock
            ?: System.getenv("GITHUB_REPOSITORY")?.takeIf { it.isNotBlank() }
            ?: "artkoder/kotopogoda-uploader-android"
        val models = json["models"] as? Map<*, *>
            ?: error("models.lock.json не содержит секцию 'models'")
        val downloadsDir = buildDir.get().asFile
        if (!downloadsDir.exists()) {
            downloadsDir.mkdirs()
        }
        val assetsDir = targetDir.get().asFile
        if (!assetsDir.exists()) {
            assetsDir.mkdirs()
        }

        models.forEach { (nameAny, payloadAny) ->
            val name = nameAny?.toString() ?: error("Имя модели отсутствует")
            val payload = payloadAny as? Map<*, *> ?: error("Модель '$name' имеет некорректный формат")
            val release = payload["release"]?.toString()?.takeIf { it.isNotBlank() }
                ?: error("Для модели '$name' не указан release")
            val assetName = payload["asset"]?.toString()?.takeIf { it.isNotBlank() }
            val modelRepository = payload["repository"]?.toString()?.takeIf { it.isNotBlank() } ?: repository
            val assetSha = payload["sha256"]?.toString()?.lowercase(Locale.US)
            val assetMinBytes = payload["min_mb"]?.let { toMegabytes(it) } ?: 0L
            val payloadUrl = payload["url"]?.toString()?.takeIf { it.isNotBlank() }
            val unzipped = payload["unzipped"]?.toString()
                ?: error("Для модели '$name' не указан unzipped")
            val unzippedPath = normaliseUnzipped(unzipped)
            val files = readFileEntries(name, payload)
            val packagingRaw = payload["packaging"]?.toString()?.lowercase(Locale.US)
            val packaging = packagingRaw
                ?: if (assetName?.endsWith(".zip", ignoreCase = true) == true) "zip" else "file"
            val isArchive = when (packaging) {
                "zip" -> true
                "file" -> false
                else -> error("Модель '$name' содержит неизвестный тип упаковки '$packaging'")
            }
            if (isArchive && assetName == null) {
                error("Для модели '$name' не указан asset")
            }
            val defaultDownloadUrl = payloadUrl
                ?: assetName?.let { "https://github.com/$modelRepository/releases/download/$release/$it" }

            val ready = files.all { entry ->
                val filePath = resolveFile(assetsDir, unzippedPath, entry.path)
                filePath.exists() && verifyFile(filePath, entry.sha256, entry.minBytes)
            }
            if (ready) {
                logger.lifecycle("Модель '$name' уже загружена, проверка пройдена")
                return@forEach
            }

            if (!isArchive) {
                cleanupTarget(assetsDir, unzippedPath, files)
                files.forEach { entry ->
                    val entryUrl = entry.url ?: defaultDownloadUrl
                        ?: error("Для файла '${entry.path}' модели '$name' не указан url")
                    val targetFile = resolveFile(assetsDir, unzippedPath, entry.path)
                    targetFile.parentFile?.mkdirs()
                    downloadFile(entryUrl, targetFile)
                    val effectiveMinBytes = maxOf(entry.minBytes, assetMinBytes)
                    if (!verifyFile(targetFile, entry.sha256, effectiveMinBytes)) {
                        throw IllegalStateException("Файл ${entry.path} не прошёл проверку SHA-256")
                    }
                }
                if (assetSha != null && files.size == 1) {
                    val entry = files.single()
                    if (!assetSha.equals(entry.sha256, ignoreCase = true)) {
                        val targetFile = resolveFile(assetsDir, unzippedPath, entry.path)
                        val actual = sha256(targetFile)
                        if (!actual.equals(assetSha, ignoreCase = true)) {
                            throw IllegalStateException("Неверный SHA-256 артефакта '${assetName ?: entry.path}': ожидалось $assetSha, получено $actual")
                        }
                    }
                }
                return@forEach
            }

            val downloadUrl = defaultDownloadUrl
                ?: error("Для модели '$name' не удалось вычислить url скачивания")
            val archiveFile = downloadsDir.resolve(assetName!!)
            downloadFile(downloadUrl, archiveFile)

            if (assetSha != null) {
                val actual = sha256(archiveFile)
                if (!actual.equals(assetSha, ignoreCase = true)) {
                    throw IllegalStateException("Неверный SHA-256 архива '$assetName': ожидалось $assetSha, получено $actual")
                }
            }

            if (assetMinBytes > 0 && archiveFile.length() < assetMinBytes) {
                throw IllegalStateException("Архив '$assetName' меньше минимального размера ${payload["min_mb"]} МБ")
            }

            cleanupTarget(assetsDir, unzippedPath, files)

            unzip(archiveFile, assetsDir)

            files.forEach { entry ->
                val modelFile = resolveFile(assetsDir, unzippedPath, entry.path)
                if (!modelFile.exists()) {
                    throw IllegalStateException("После распаковки отсутствует файл ${entry.path} для модели '$name'")
                }
                if (!verifyFile(modelFile, entry.sha256, entry.minBytes)) {
                    throw IllegalStateException("Файл ${entry.path} не прошёл проверку SHA-256")
                }
            }
        }

        println("ok=true")
    }

    private fun toMegabytes(value: Any?): Long {
        val number = when (value) {
            is BigDecimal -> value.toDouble()
            is Number -> value.toDouble()
            is String -> value.toDoubleOrNull()
            else -> null
        } ?: return 0L
        return (number * 1024.0 * 1024.0).roundToLong()
    }

    private fun readFileEntries(name: String, payload: Map<*, *>): List<FileEntry> {
        val filesAny = payload["files"] as? List<*>
        require(!filesAny.isNullOrEmpty()) { "Для модели '$name' не указаны файлы" }
        return filesAny.mapIndexed { index, item ->
            val data = item as? Map<*, *> ?: error("Файл №${index + 1} модели '$name' имеет неверный формат")
            val path = data["path"]?.toString()?.takeIf { it.isNotBlank() }
                ?: error("Для модели '$name' не указан путь файла")
            val sha = data["sha256"]?.toString()?.lowercase(Locale.US)
                ?: error("Для файла '$path' не указан sha256")
            val minBytes = data["min_mb"]?.let { toMegabytes(it) } ?: 0L
            val url = data["url"]?.toString()?.takeIf { it.isNotBlank() }
            FileEntry(path, sha, minBytes, url)
        }
    }

    private fun normaliseUnzipped(value: String): Path? {
        val trimmed = value.trim()
        if (trimmed.isEmpty() || trimmed == ".") {
            return null
        }
        val path = Paths.get(trimmed).normalize()
        require(!path.isAbsolute) { "Поле 'unzipped' для модели должно быть относительным путём" }
        for (name in path) {
            require(name.toString() != "..") { "Поле 'unzipped' не должно содержать '..'" }
        }
        return if (path.nameCount == 0) null else path
    }

    private fun resolveRootDir(baseDir: java.io.File, root: Path?): java.io.File {
        val basePath = baseDir.toPath()
        val resolved = root?.let { basePath.resolve(it).normalize() } ?: basePath
        if (!resolved.startsWith(basePath)) {
            throw IllegalStateException("Каталог распаковки выходит за пределы ${baseDir.absolutePath}")
        }
        return resolved.toFile()
    }

    private fun resolveFile(baseDir: java.io.File, root: Path?, relativePath: String): java.io.File {
        val normalisedRelative = Paths.get(relativePath).normalize()
        require(!normalisedRelative.isAbsolute) { "Пути файлов моделей должны быть относительными" }
        for (name in normalisedRelative) {
            require(name.toString() != "..") { "Пути файлов моделей не должны содержать '..'" }
        }
        val rootDir = resolveRootDir(baseDir, root).toPath()
        val target = rootDir.resolve(normalisedRelative).normalize()
        if (!target.startsWith(baseDir.toPath())) {
            throw IllegalStateException("Путь файла выходит за пределы каталога моделей: $target")
        }
        return target.toFile()
    }

    private fun cleanupTarget(baseDir: java.io.File, root: Path?, files: List<FileEntry>) {
        if (root != null) {
            val rootDir = resolveRootDir(baseDir, root)
            if (rootDir.exists()) {
                rootDir.deleteRecursively()
            }
        } else {
            files.forEach { entry ->
                val existing = resolveFile(baseDir, null, entry.path)
                if (existing.exists()) {
                    existing.delete()
                }
            }
        }
    }

    private fun verifyFile(file: java.io.File, expectedSha: String, minBytes: Long): Boolean {
        if (!file.exists()) return false
        if (minBytes > 0 && file.length() < minBytes) {
            return false
        }
        val actual = sha256(file)
        return actual.equals(expectedSha, ignoreCase = true)
    }

    private fun downloadFile(url: String, destination: java.io.File) {
        logger.lifecycle("Скачивание $url -> ${destination.absolutePath}")
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = true
        try {
            connection.inputStream.use { input ->
                destination.parentFile?.mkdirs()
                destination.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun unzip(archive: java.io.File, target: java.io.File) {
        ZipInputStream(archive.inputStream().buffered()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val outFile = target.resolve(entry.name)
                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    outFile.outputStream().use { output ->
                        zip.copyTo(output)
                    }
                }
                entry = zip.nextEntry
            }
        }
    }

    private fun sha256(file: java.io.File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            DigestInputStream(input, digest).use { stream ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (stream.read(buffer) != -1) {
                    // читаем до конца
                }
            }
        }
        return digest.digest().joinToString(separator = "") { byte ->
            "%02x".format(byte)
        }
    }

    private data class FileEntry(
        val path: String,
        val sha256: String,
        val minBytes: Long,
        val url: String?,
    )
}

tasks.register<FetchModelsTask>("fetchModels")

project(":app") {
    val rootFetch = rootProject.tasks.named("fetchModels")
    tasks.register("fetchModels") {
        group = "models"
        description = "Прокси для корневой задачи fetchModels"
        dependsOn(rootFetch)
    }
    tasks.matching { it.name == "preBuild" }.configureEach {
        dependsOn(rootFetch)
    }
}

// Глобальные настройки для JVM unit-тестов
subprojects {
    tasks.withType<Test>().configureEach {
        // Создать директорию для heap dumps заранее
        doFirst {
            project.file("${project.buildDir}/test-heap-dumps").mkdirs()
        }
        
        // Ограничение параллелизма для стабильности
        maxParallelForks = 1
        
        // Форкать процесс каждые 50 тестов для предотвращения утечек памяти
        forkEvery = 50
        
        // Останавливать выполнение при первой ошибке для быстрой обратной связи
        failFast = true
        
        // Ограничение heap для предотвращения OOM
        maxHeapSize = "1g"
        
        // JVM аргументы для ограничения ресурсов и диагностики
        jvmArgs(
            // Ограничение RAM
            "-XX:MaxRAMPercentage=70",
            // Создать heap dump при OOM для диагностики
            "-XX:+HeapDumpOnOutOfMemoryError",
            "-XX:HeapDumpPath=${project.buildDir}/test-heap-dumps/heap-%t.bin",
            // Ограничить число потоков в coroutines scheduler (>=4 для совместимости с core pool size)
            "-Dkotlinx.coroutines.scheduler.max.pool.size=8",
            // Отключить coroutines debug для производительности
            "-Dkotlinx.coroutines.debug=off"
        )
        
        // Настройка Robolectric
        systemProperty("robolectric.logging.enabled", "false")
        systemProperty(
            "robolectric.offline",
            System.getenv("ROBOLECTRIC_OFFLINE")?.ifBlank { null } ?: "false"
        )
        
        // Показывать stdout/stderr для лучшей диагностики
        testLogging {
            events("passed", "skipped", "failed", "standardOut", "standardError")
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
            showExceptions = true
            showCauses = true
            showStackTraces = true
        }
    }
}
