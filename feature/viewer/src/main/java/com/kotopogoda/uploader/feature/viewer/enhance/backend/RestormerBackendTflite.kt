package com.kotopogoda.uploader.feature.viewer.enhance.backend

import android.content.Context
import com.kotopogoda.uploader.feature.viewer.enhance.EnhanceEngine
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
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
class RestormerBackendTflite @Inject constructor(
    @ApplicationContext private val context: Context,
) : EnhanceEngine.RestormerModel {

    override suspend fun denoise(
        tile: EnhanceEngine.ImageBuffer,
        delegate: EnhanceEngine.Delegate,
    ): EnhanceEngine.ModelResult = withContext(Dispatchers.IO) {
        val preferences = when (delegate) {
            EnhanceEngine.Delegate.GPU -> listOf(DelegatePreference.GPU, DelegatePreference.CPU)
            EnhanceEngine.Delegate.CPU -> listOf(DelegatePreference.CPU)
        }
        var lastError: Throwable? = null
        for (preference in preferences) {
            try {
                val result = runDenoise(tile.copy(), preference)
                Timber.tag(TAG).i(
                    "Restormer delegate resolved: requested=%s, actual=%s",
                    delegate,
                    result.delegate,
                )
                return@withContext result
            } catch (error: Exception) {
                lastError = error
                Timber.tag(TAG).w(error, "Restormer delegate %s failed", preference)
            }
        }
        if (lastError != null) {
            Timber.tag(TAG).w(lastError, "Restormer inference failed, returning original tile")
        }
        return@withContext EnhanceEngine.ModelResult(tile.copy(), EnhanceEngine.Delegate.CPU)
    }

    private fun runDenoise(
        tile: EnhanceEngine.ImageBuffer,
        preference: DelegatePreference,
    ): EnhanceEngine.ModelResult {
        val options = Interpreter.Options()
        var gpuDelegate: Delegate? = null
        try {
            when (preference) {
                DelegatePreference.GPU -> {
                    gpuDelegate = GpuDelegate()
                    options.addDelegate(gpuDelegate)
                }
                DelegatePreference.CPU -> {
                    options.setUseXNNPACK(true)
                }
            }
            Interpreter(loadModel(RESTORMER_MODEL), options).use { }
        } finally {
            gpuDelegate?.close()
        }
        return EnhanceEngine.ModelResult(tile, preference.asEngineDelegate())
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

    private enum class DelegatePreference {
        GPU,
        CPU;

        fun asEngineDelegate(): EnhanceEngine.Delegate = when (this) {
            GPU -> EnhanceEngine.Delegate.GPU
            CPU -> EnhanceEngine.Delegate.CPU
        }
    }

    companion object {
        private const val TAG = "Enhance/Restormer"
        private const val RESTORMER_MODEL = "restormer.bin"
    }
}
