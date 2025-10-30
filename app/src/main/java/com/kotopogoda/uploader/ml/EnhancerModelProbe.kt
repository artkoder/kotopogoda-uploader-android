package com.kotopogoda.uploader.ml

import android.content.Context
import android.content.res.AssetManager
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
import kotlin.math.max
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.GpuDelegate
import timber.log.Timber
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

object EnhancerModelProbe {

    private const val TAG = "Enhance/Probe"

    fun run(context: Context) {
        val start = SystemClock.elapsedRealtime()
        EnhanceLogging.clearProbeSummary()
        runCatching {
            val modelsLock = ModelsLockParser.parse(BuildConfig.MODELS_LOCK_JSON)
            val assetManager = context.assets
            val results = mutableMapOf<String, EnhanceLogging.ModelSummary>()
            modelsLock.models.values.forEach { definition ->
                val summary = probeModel(context, assetManager, definition)
                if (summary != null) {
                    results[definition.name] = summary
                }
            }
            EnhanceLogging.updateProbeSummary(EnhanceLogging.ProbeSummary(results))
        }.onFailure { error ->
            Timber.tag(TAG).e(error, "Не удалось выполнить проверку моделей улучшения")
        }
        Timber.tag(TAG).i(
            UploadLog.message(
                category = "ENHANCE/PROBE",
                action = "enhancer_probe",
                details = arrayOf(
                    "duration_ms" to (SystemClock.elapsedRealtime() - start),
                ),
            ),
        )
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
        return runCatching {
            val info = readAssetInfo(assetManager, file.path)
            val checksumOk = info.sha.equals(file.sha256, ignoreCase = true)
            val minOk = file.minBytes <= 0 || info.bytes >= file.minBytes
            if (!checksumOk) {
                Timber.tag(TAG).w(
                    "Несовпадение SHA-256 для %s: ожидается=%s, фактически=%s",
                    file.path,
                    file.sha256,
                    info.sha,
                )
            }
            if (!minOk) {
                Timber.tag(TAG).w(
                    "Размер файла %s меньше ожидаемого: %d < %d",
                    file.path,
                    info.bytes,
                    file.minBytes,
                )
            }
            EnhanceLogging.FileSummary(
                path = file.path,
                bytes = info.bytes,
                checksum = info.sha,
                expectedChecksum = file.sha256,
                checksumOk = checksumOk,
                minBytes = file.minBytes,
                minBytesOk = minOk,
            )
        }.getOrElse { error ->
            Timber.tag(TAG).e(error, "Не удалось прочитать файл модели %s", file.path)
            EnhanceLogging.FileSummary(
                path = file.path,
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
            ModelBackend.TFLITE -> probeTfliteDelegates(context, definition)
            ModelBackend.NCNN -> mapOf(
                "ncnn" to EnhanceLogging.DelegateSummary(
                    available = false,
                    warmupMillis = null,
                    error = "ncnn_probe_not_implemented",
                ),
            )
        }
    }

    private fun probeTfliteDelegates(
        context: Context,
        definition: ModelDefinition,
    ): Map<String, EnhanceLogging.DelegateSummary> {
        val tfliteFile = definition.files.firstOrNull { it.path.endsWith(".tflite") } ?: definition.files.firstOrNull()
        if (tfliteFile == null) {
            Timber.tag(TAG).w("Для модели %s не найден TFLite-файл", definition.name)
            return emptyMap()
        }
        val delegates = mutableMapOf<String, EnhanceLogging.DelegateSummary>()
        delegates["gpu"] = runDelegateCheck("gpu") { checkGpuDelegate(context, tfliteFile.path) }
        delegates["xnnpack"] = runDelegateCheck("xnnpack") { checkXnnpackDelegate(context, tfliteFile.path) }
        return delegates
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

    private fun checkGpuDelegate(context: Context, assetPath: String): Long {
        val options = Interpreter.Options()
        var delegate: GpuDelegate? = null
        return try {
            val start = SystemClock.elapsedRealtimeNanos()
            delegate = GpuDelegate()
            options.addDelegate(delegate)
            Interpreter(loadModel(context, assetPath), options).use { interpreter ->
                interpreter.allocateTensors()
            }
            (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000
        } finally {
            delegate?.close()
        }
    }

    private fun checkXnnpackDelegate(context: Context, assetPath: String): Long {
        val options = Interpreter.Options()
        options.setUseXNNPACK(true)
        options.setNumThreads(max(1, Runtime.getRuntime().availableProcessors() - 1))
        val start = SystemClock.elapsedRealtimeNanos()
        Interpreter(loadModel(context, assetPath), options).use { interpreter ->
            interpreter.allocateTensors()
        }
        return (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000
    }

    private fun loadModel(context: Context, assetPath: String): MappedByteBuffer {
        val assetManager = context.assets
        val descriptor = assetManager.openFd(assetPath)
        descriptor.use { afd ->
            FileInputStream(afd.fileDescriptor).use { stream ->
                val channel: FileChannel = stream.channel
                return channel.map(FileChannel.MapMode.READ_ONLY, afd.startOffset, afd.length)
            }
        }
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

    private const val DEFAULT_BUFFER_SIZE = 8 * 1024
}
