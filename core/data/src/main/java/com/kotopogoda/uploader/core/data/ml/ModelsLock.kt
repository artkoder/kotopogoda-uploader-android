package com.kotopogoda.uploader.core.data.ml

import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

/**
 * Представление содержимого файла models.lock.json.
 */
data class ModelsLock(
    val repository: String?,
    val models: Map<String, ModelDefinition>,
) {
    fun get(name: String): ModelDefinition? = models[name]

    fun require(name: String): ModelDefinition = models[name]
        ?: throw IllegalArgumentException("Модель '$name' отсутствует в models.lock.json")
}

data class ModelDefinition(
    val name: String,
    val release: String,
    val asset: String,
    val sha256: String?,
    val backend: ModelBackend,
    val minBytes: Long,
    val files: List<ModelFile>,
) {
    fun filesByExtension(): Map<String, ModelFile> = files.associateBy { file ->
        file.path.substringAfterLast('.', missingDelimiterValue = file.path)
            .lowercase(Locale.US)
    }
}

data class ModelFile(
    val path: String,
    val sha256: String,
    val minBytes: Long,
)

enum class ModelBackend { TFLITE, NCNN }

object ModelsLockParser {
    fun parse(rawJson: String): ModelsLock {
        val root = JSONObject(rawJson)
        val repository = root.optString("repository").takeIf { it.isNotBlank() }
        val modelsObject = root.optJSONObject("models")
            ?: throw IllegalArgumentException("models.lock.json: отсутствует объект 'models'")
        val models = mutableMapOf<String, ModelDefinition>()
        val keys = modelsObject.keys()
        while (keys.hasNext()) {
            val name = keys.next()
            val definition = modelsObject.getJSONObject(name)
            models[name] = parseDefinition(name, definition)
        }
        return ModelsLock(repository, models)
    }

    private fun parseDefinition(name: String, json: JSONObject): ModelDefinition {
        val release = json.getString("release")
        val asset = json.getString("asset")
        val shaRaw = if (json.isNull("sha256")) null else json.optString("sha256")
        val sha = shaRaw?.takeIf { it.isNotBlank() }?.lowercase(Locale.US)
        val backendValue = json.getString("backend").uppercase(Locale.US)
        val backend = runCatching { ModelBackend.valueOf(backendValue) }
            .getOrElse {
                val supportedBackends = ModelBackend.values().joinToString { it.name }
                throw IllegalArgumentException(
                    "models.lock.json: модель '$name' использует неизвестный backend '$backendValue'. " +
                    "Поддерживаемые backends: $supportedBackends. " +
                    "Возможные причины: устаревшая версия пакета моделей, несовместимость с текущей версией приложения."
                )
            }
        val minBytes = megabytesToBytes(json.optDouble("min_mb", 0.0))
        val filesArray = json.optJSONArray("files")
            ?: throw IllegalArgumentException("models.lock.json: модель '$name' не содержит массива files")
        if (filesArray.length() == 0) {
            throw IllegalArgumentException("models.lock.json: модель '$name' не содержит описания файлов")
        }
        val files = parseFiles(filesArray, minBytes)
        return ModelDefinition(
            name = name,
            release = release,
            asset = asset,
            sha256 = sha,
            backend = backend,
            minBytes = minBytes,
            files = files,
        )
    }

    private fun parseFiles(array: JSONArray, fallbackMinBytes: Long): List<ModelFile> {
        val files = ArrayList<ModelFile>(array.length())
        for (index in 0 until array.length()) {
            val item = array.getJSONObject(index)
            val path = item.getString("path")
            val sha = item.getString("sha256").lowercase(Locale.US)
            val minBytes = if (item.isNull("min_mb")) fallbackMinBytes
            else megabytesToBytes(item.optDouble("min_mb", 0.0))
            files += ModelFile(path = path, sha256 = sha, minBytes = minBytes)
        }
        return files
    }

    private fun megabytesToBytes(value: Double): Long {
        if (value <= 0.0) return 0L
        return (value * 1024.0 * 1024.0).toLong()
    }
}
