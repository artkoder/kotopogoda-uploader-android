import groovy.json.JsonSlurper
import java.math.BigDecimal
import java.net.HttpURLConnection
import java.net.URL
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.ZipInputStream
import kotlin.io.DEFAULT_BUFFER_SIZE
import kotlin.math.roundToLong
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
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

    @get:InputDirectory
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
            ?: "kotopogoda/kotopogoda-uploader-android"
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
                ?: error("Для модели '$name' не указан asset")
            val assetSha = payload["sha256"]?.toString()?.lowercase(Locale.US)
            val assetMinBytes = payload["min_mb"]?.let { toMegabytes(it) } ?: 0L
            val files = readFileEntries(name, payload)

            val ready = files.all { entry ->
                val filePath = assetsDir.resolve(entry.path)
                filePath.exists() && verifyFile(filePath, entry.sha256, entry.minBytes)
            }
            if (ready) {
                logger.lifecycle("Модель '$name' уже загружена, проверка пройдена")
                return@forEach
            }

            val downloadUrl = "https://github.com/$repository/releases/download/$release/$assetName"
            val archiveFile = downloadsDir.resolve(assetName)
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

            unzip(archiveFile, assetsDir)

            files.forEach { entry ->
                val modelFile = assetsDir.resolve(entry.path)
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
            FileEntry(path, sha, minBytes)
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
