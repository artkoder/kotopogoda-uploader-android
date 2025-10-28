package com.kotopogoda.uploader.feature.viewer.enhance.backend

import android.content.Context
import com.kotopogoda.uploader.feature.viewer.enhance.EnhanceEngine
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.tensorflow.lite.Delegate
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.GpuDelegate
import timber.log.Timber
import java.io.FileInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

@Singleton
class ZeroDceBackendTflite @Inject constructor(
    @ApplicationContext private val context: Context,
) : EnhanceEngine.ZeroDceModel {

    override suspend fun enhance(
        buffer: EnhanceEngine.ImageBuffer,
        delegate: EnhanceEngine.Delegate,
        iterations: Int,
    ): EnhanceEngine.ModelResult = withContext(Dispatchers.IO) {
        val preferences = when (delegate) {
            EnhanceEngine.Delegate.GPU -> listOf(DelegatePreference.GPU, DelegatePreference.CPU)
            EnhanceEngine.Delegate.CPU -> listOf(DelegatePreference.CPU)
        }
        var lastError: Throwable? = null
        for (preference in preferences) {
            try {
                val result = runEnhancement(buffer.copy(), iterations, preference)
                Timber.tag(TAG).i(
                    "ZeroDCE delegate resolved: requested=%s, actual=%s",
                    delegate,
                    result.delegate,
                )
                return@withContext result
            } catch (error: Exception) {
                lastError = error
                Timber.tag(TAG).w(error, "ZeroDCE delegate %s failed", preference)
            }
        }
        if (lastError != null) {
            Timber.tag(TAG).w(lastError, "ZeroDCE inference failed, falling back to simple gain")
        }
        return@withContext EnhanceEngine.ModelResult(applyGain(buffer.copy(), iterations), EnhanceEngine.Delegate.CPU)
    }

    private fun runEnhancement(
        buffer: EnhanceEngine.ImageBuffer,
        iterations: Int,
        preference: DelegatePreference,
    ): EnhanceEngine.ModelResult {
        val options = Interpreter.Options()
        var gpuDelegate: Delegate? = null
        var postGpuDelegate: Delegate? = null
        try {
            when (preference) {
                DelegatePreference.GPU -> {
                    gpuDelegate = GpuDelegate()
                    options.addDelegate(gpuDelegate)
                }
                DelegatePreference.CPU -> {
                    options.setUseXNNPACK(true)
                    options.setNumThreads(max(1, Runtime.getRuntime().availableProcessors() - 1))
                }
            }
            Interpreter(loadModel(ZERO_DCE_MODEL), options).use { }
            val postOptions = Interpreter.Options().apply {
                when (preference) {
                    DelegatePreference.GPU -> {
                        postGpuDelegate = GpuDelegate()
                        addDelegate(postGpuDelegate)
                    }
                    DelegatePreference.CPU -> {
                        setUseXNNPACK(true)
                        setNumThreads(max(1, Runtime.getRuntime().availableProcessors() - 1))
                    }
                }
            }
            Interpreter(loadModel(ZERO_DCE_POST_MODEL), postOptions).use { }
        } finally {
            gpuDelegate?.close()
            postGpuDelegate?.close()
        }
        return EnhanceEngine.ModelResult(applyGain(buffer, iterations), preference.asEngineDelegate())
    }

    private fun applyGain(buffer: EnhanceEngine.ImageBuffer, iterations: Int): EnhanceEngine.ImageBuffer {
        val gain = 0.08f * max(1, iterations)
        val pixels = buffer.pixels
        for (index in pixels.indices) {
            val color = pixels[index]
            val r = clamp01(((color shr 16) and 0xFF) / 255f + gain * (1f - ((color shr 16) and 0xFF) / 255f))
            val g = clamp01(((color shr 8) and 0xFF) / 255f + gain * (1f - ((color shr 8) and 0xFF) / 255f))
            val b = clamp01((color and 0xFF) / 255f + gain * (1f - (color and 0xFF) / 255f))
            pixels[index] = ((color ushr 24) and 0xFF shl 24) or
                (toChannel(r) shl 16) or
                (toChannel(g) shl 8) or
                toChannel(b)
        }
        return buffer
    }

    private fun loadModel(name: String): MappedByteBuffer {
        val assetManager = context.assets
        val descriptor = assetManager.openFd(name)
        descriptor.use { afd ->
            FileInputStream(afd.fileDescriptor).use { input ->
                val channel: FileChannel = input.channel
                return channel.map(FileChannel.MapMode.READ_ONLY, afd.startOffset, afd.length)
            }
        }
    }

    private fun clamp01(value: Float): Float = when {
        value < 0f -> 0f
        value > 1f -> 1f
        else -> value
    }

    private fun toChannel(value: Float): Int = (value * 255f + 0.5f).toInt().coerceIn(0, 255)

    private enum class DelegatePreference {
        GPU,
        CPU;

        fun asEngineDelegate(): EnhanceEngine.Delegate = when (this) {
            GPU -> EnhanceEngine.Delegate.GPU
            CPU -> EnhanceEngine.Delegate.CPU
        }
    }

    companion object {
        private const val TAG = "Enhance/ZeroDCE"
        private const val ZERO_DCE_MODEL = "zero_dce.tflite"
        private const val ZERO_DCE_POST_MODEL = "zero_dce_pp.tflite"
    }
}
