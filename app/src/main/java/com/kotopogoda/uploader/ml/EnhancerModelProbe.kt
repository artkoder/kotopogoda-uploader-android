package com.kotopogoda.uploader.ml

import android.content.Context
import android.content.res.AssetManager
import android.os.Build
import android.os.SystemClock
import com.kotopogoda.uploader.BuildConfig
import com.kotopogoda.uploader.core.data.ml.ModelBackend
import com.kotopogoda.uploader.core.data.ml.ModelDefinition
import com.kotopogoda.uploader.core.data.ml.ModelFile
import com.kotopogoda.uploader.core.data.ml.ModelsLockParser
import com.kotopogoda.uploader.core.data.upload.UploadLog
import com.kotopogoda.uploader.feature.viewer.enhance.EnhanceLogging
import java.io.BufferedInputStream
import java.io.FileInputStream
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import timber.log.Timber

object EnhancerModelProbe {

    private const val TAG = "Enhance/Probe"
    private val probeRunning = AtomicBoolean(false)
    private val lastProbeSignature = AtomicReference<String?>(null)

    fun run(context: Context) {
        val modelsSignature = currentModelsSignature()
        if (isProbeSummaryFresh(modelsSignature)) {
            Timber.tag(TAG).d("Результаты проверки моделей актуальны, повторный запуск не требуется")
            return
        }
        if (!probeRunning.compareAndSet(false, true)) {
            Timber.tag(TAG).d("Проверка моделей уже выполняется, пропускаем повторный запуск")
            return
        }
        try {
            executeProbe(context, modelsSignature)
        } finally {
            probeRunning.set(false)
        }
    }

    private fun isProbeSummaryFresh(modelsSignature: String): Boolean {
        val cachedSummary = EnhanceLogging.probeSummary
        val cachedSignature = lastProbeSignature.get()
        return cachedSummary != null && cachedSignature == modelsSignature
    }

    private fun currentModelsSignature(): String {
        return BuildConfig.MODELS_LOCK_JSON.hashCode().toString()
    }

    private fun executeProbe(context: Context, modelsSignature: String) {
        val start = SystemClock.elapsedRealtime()
        EnhanceLogging.clearProbeSummary()
        val probeResult = runCatching {
            val modelsLock = ModelsLockParser.parse(BuildConfig.MODELS_LOCK_JSON)
            val assetManager = context.assets
            val results = mutableMapOf<String, EnhanceLogging.ModelSummary>()
            modelsLock.models.values.forEach { definition ->
                if (!definition.enabled) {
                    Timber.tag(TAG).i(
                        "Модель %s отключена (precision=%s), пропускаем проверку",
                        definition.name,
                        definition.precision ?: "—",
                    )
                    return@forEach
                }
                val summary = probeModel(context, assetManager, definition)
                if (summary != null) {
                    results[definition.name] = summary
                }
            }
            EnhanceLogging.updateProbeSummary(EnhanceLogging.ProbeSummary(results))
            results
        }.onFailure { error ->
            Timber.tag(TAG).e(error, "Не удалось выполнить проверку моделей улучшения")
        }
        val duration = SystemClock.elapsedRealtime() - start
        Timber.tag(TAG).i(
            UploadLog.message(
                category = "ENHANCE/PROBE",
                action = "enhancer_probe",
                details = arrayOf(
                    "duration_ms" to duration,
                ),
            ),
        )
        probeResult.onSuccess { summaries ->
            EnhanceLogging.logEvent(
                "enhancer_probe",
                mapOf(
                    "duration_ms" to duration,
                    "models_total" to summaries.size,
                    "status" to "success",
                ) + defaultProbeMetadata(),
            )
            lastProbeSignature.set(modelsSignature)
        }.onFailure { error ->
            EnhanceLogging.logEvent(
                "enhancer_probe",
                mapOf(
                    "duration_ms" to duration,
                    "status" to "failure",
                    "error" to (error.message ?: error.javaClass.simpleName),
                ) + defaultProbeMetadata(),
            )
        }
    }

    private fun probeModel(
        context: Context,
        assetManager: AssetManager,
        definition: ModelDefinition,
    ): EnhanceLogging.ModelSummary? {
        val backendName = definition.backend.name.lowercase(Locale.US)
        val fileSummaries = definition.files.map { file ->
            computeFileSummary(assetManager, file)
        }
        val delegateSummaries = probeDelegates(context, definition)
        logModelSummary(definition, fileSummaries, delegateSummaries)
        return EnhanceLogging.ModelSummary(
            backend = backendName,
            files = fileSummaries,
            delegates = delegateSummaries,
        )
    }

    private fun computeFileSummary(
        assetManager: AssetManager,
        file: ModelFile,
    ): EnhanceLogging.FileSummary {
        val assetPath = file.assetPath()
        return runCatching {
            val info = readAssetInfo(assetManager, assetPath)
            val checksumOk = info.sha.equals(file.sha256, ignoreCase = true)
            val minOk = file.minBytes <= 0 || info.bytes >= file.minBytes
            if (!checksumOk) {
                Timber.tag(TAG).w(
                    "Несовпадение SHA-256 для %s: ожидается=%s, фактически=%s",
                    assetPath,
                    file.sha256,
                    info.sha,
                )
            }
            if (!minOk) {
                Timber.tag(TAG).w(
                    "Размер файла %s меньше ожидаемого: %d < %d",
                    assetPath,
                    info.bytes,
                    file.minBytes,
                )
            }
            EnhanceLogging.FileSummary(
                path = assetPath,
                bytes = info.bytes,
                checksum = info.sha,
                expectedChecksum = file.sha256,
                checksumOk = checksumOk,
                minBytes = file.minBytes,
                minBytesOk = minOk,
            )
        }.getOrElse { error ->
            Timber.tag(TAG).e(error, "Не удалось прочитать файл модели %s", assetPath)
            EnhanceLogging.FileSummary(
                path = assetPath,
                bytes = -1L,
                checksum = "",
                expectedChecksum = file.sha256,
                checksumOk = false,
                minBytes = file.minBytes,
                minBytesOk = false,
            )
        }
    }

    private fun probeDelegates(
        context: Context,
        definition: ModelDefinition,
    ): Map<String, EnhanceLogging.DelegateSummary> {
        return when (definition.backend) {
            ModelBackend.TFLITE -> emptyMap()
            ModelBackend.NCNN -> probeNcnnDelegates(context, definition)
        }
    }

    private fun probeNcnnDelegates(
        context: Context,
        definition: ModelDefinition,
    ): Map<String, EnhanceLogging.DelegateSummary> {
        val paramFile = definition.files.firstOrNull { it.path.endsWith(".param") }
        val binFile = definition.files.firstOrNull { it.path.endsWith(".bin") }
        if (paramFile == null || binFile == null) {
            Timber.tag(TAG).w("Для модели %s не найдены .param/.bin файлы", definition.name)
            return mapOf(
                "ncnn" to EnhanceLogging.DelegateSummary(
                    available = false,
                    warmupMillis = null,
                    error = "missing_param_or_bin",
                ),
            )
        }
        val paramAsset = paramFile.assetPath()
        val binAsset = binFile.assetPath()
        return mapOf(
            "ncnn" to runDelegateCheck("ncnn") { checkNcnnDelegate(context, paramAsset, binAsset) },
        )
    }

    private inline fun runDelegateCheck(
        name: String,
        block: () -> Long,
    ): EnhanceLogging.DelegateSummary {
        return runCatching {
            val warmup = block()
            EnhanceLogging.DelegateSummary(
                available = true,
                warmupMillis = warmup,
                error = null,
            )
        }.getOrElse { error ->
            Timber.tag(TAG).w(error, "Проверка делегата %s завершилась с ошибкой", name)
            EnhanceLogging.DelegateSummary(
                available = false,
                warmupMillis = null,
                error = error.message ?: error.javaClass.simpleName,
            )
        }
    }

    private fun checkNcnnDelegate(context: Context, paramPath: String, binPath: String): Long {
        val assetManager = context.assets
        val start = SystemClock.elapsedRealtimeNanos()
        assetManager.open(paramPath).use { paramStream ->
            val paramBytes = paramStream.readBytes()
            if (paramBytes.isEmpty()) {
                throw IllegalStateException("Файл .param пустой")
            }
        }
        assetManager.open(binPath).use { binStream ->
            val binBytes = binStream.readBytes()
            if (binBytes.isEmpty()) {
                throw IllegalStateException("Файл .bin пустой")
            }
        }
        return (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000
    }

    private data class AssetInfo(
        val bytes: Long,
        val sha: String,
    )

    private fun readAssetInfo(assetManager: AssetManager, path: String): AssetInfo {
        val digest = MessageDigest.getInstance("SHA-256")
        var totalBytes = 0L
        assetManager.open(path).use { input ->
            DigestInputStream(BufferedInputStream(input), digest).use { stream ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = stream.read(buffer)
                    if (read <= 0) break
                    totalBytes += read
                }
            }
        }
        val sha = digest.digest().joinToString(separator = "") { byte -> "%02x".format(byte) }
        return AssetInfo(bytes = totalBytes, sha = sha)
    }

    private fun logModelSummary(
        definition: ModelDefinition,
        files: List<EnhanceLogging.FileSummary>,
        delegates: Map<String, EnhanceLogging.DelegateSummary>,
    ) {
        val details = mutableListOf<Pair<String, Any?>>()
        details += "model" to definition.name
        details += "backend" to definition.backend.name.lowercase(Locale.US)
        files.forEachIndexed { index, file ->
            val prefix = if (files.size == 1) "" else "${index}_"
            details += "${prefix}asset" to file.path
            details += "${prefix}bytes" to file.bytes
            details += "${prefix}min_bytes" to file.minBytes
            details += "${prefix}min_ok" to file.minBytesOk
            details += "${prefix}sha256_expected" to file.expectedChecksum
            details += "${prefix}sha256_actual" to file.checksum
            details += "${prefix}sha256_ok" to file.checksumOk
        }
        delegates.forEach { (name, summary) ->
            details += "delegate_${name}_ok" to summary.available
            summary.warmupMillis?.let { warmup ->
                details += "delegate_${name}_warmup_ms" to warmup
            }
            summary.error?.takeIf { it.isNotBlank() }?.let { message ->
                details += "delegate_${name}_error" to message
            }
        }
        Timber.tag(TAG).i(
            UploadLog.message(
                category = "ENHANCE/PROBE",
                action = "enhancer_probe",
                details = details.toTypedArray(),
            ),
        )
    }

    private fun defaultProbeMetadata(): Map<String, Any?> = mapOf(
        "backend" to "ncnn_cpu",
        "vulkan_available" to false,
        "delegate_plan" to "cpu",
        "delegate_available" to "cpu_only",
        "delegate_used" to "cpu",
        "force_cpu" to true,
        "tile_default" to TILE_DEFAULT,
        "app_version" to BuildConfig.VERSION_NAME,
        "android_sdk" to Build.VERSION.SDK_INT,
    )

    private const val DEFAULT_BUFFER_SIZE = 8 * 1024
    private const val TILE_DEFAULT = 384
}
