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
import java.security.MessageDigest
import kotlin.io.DEFAULT_BUFFER_SIZE
import kotlin.math.max

@Singleton
class RestormerBackendTflite @Inject constructor(
    @ApplicationContext private val context: Context,
) : EnhanceEngine.RestormerModel {

    override val checksum: String by lazy { computeChecksum(RESTORMER_MODEL) }

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
                val result = runDenoise(tile, preference)
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
        throw lastError ?: IllegalStateException("Restormer inference failed")
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
                    gpuDelegate = createGpuDelegate() ?: throw IllegalStateException("GPU delegate unavailable")
                    options.addDelegate(gpuDelegate)
                }
                DelegatePreference.CPU -> {
                    options.setUseXNNPACK(true)
                    options.setNumThreads(max(1, Runtime.getRuntime().availableProcessors() - 1))
                }
            }

            Interpreter(loadModel(RESTORMER_MODEL), options).use { interpreter ->
                val inputTensor = interpreter.getInputTensor(0)
                val outputTensor = interpreter.getOutputTensor(0)
                require(inputTensor.shape().size >= 4) { "Restormer input tensor shape is invalid" }
                require(outputTensor.shape().size >= 4) { "Restormer output tensor shape is invalid" }
                val inputShape = inputTensor.shape()
                val outputShape = outputTensor.shape()
                val inputHeight = inputShape[1]
                val inputWidth = inputShape[2]
                val inputChannels = inputShape[3]
                val outputHeight = outputShape[1]
                val outputWidth = outputShape[2]
                val outputChannels = outputShape[3]
                require(inputChannels == 3 && outputChannels == 3) { "Restormer expects RGB tensors" }

                val prepared = TfliteImageOps.prepareInput(tile, inputWidth, inputHeight)
                val inputFloats = prepared.floats
                val outputFloats = FloatArray(outputWidth * outputHeight * outputChannels)
                val inputBuffer = TfliteImageOps.allocateBuffer(inputTensor.dataType(), inputFloats.size)
                val outputBuffer = TfliteImageOps.allocateBuffer(outputTensor.dataType(), outputFloats.size)

                TfliteImageOps.writeToBuffer(inputFloats, inputBuffer, inputTensor.dataType())
                inputBuffer.rewind()
                outputBuffer.rewind()
                interpreter.run(inputBuffer, outputBuffer)
                TfliteImageOps.readFromBuffer(outputBuffer, outputTensor.dataType(), outputFloats)

                val result = TfliteImageOps.buildImageBuffer(
                    floats = outputFloats,
                    width = outputWidth,
                    height = outputHeight,
                    originalWidth = prepared.originalWidth,
                    originalHeight = prepared.originalHeight,
                )
                return EnhanceEngine.ModelResult(result, preference.asEngineDelegate())
            }
        } finally {
            gpuDelegate?.close()
        }
    }

    private fun createGpuDelegate(): Delegate? = runCatching { GpuDelegate() }
        .onFailure { Timber.tag(TAG).w(it, "Unable to create GPU delegate") }
        .getOrNull()

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

    private fun computeChecksum(asset: String): String = runCatching {
        val digest = MessageDigest.getInstance("SHA-256")
        context.assets.open(asset).use { stream ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = stream.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }.getOrElse { error -> throw IllegalStateException("Unable to compute checksum for $asset", error) }

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
        private const val RESTORMER_MODEL = "models/restormer_fp16.tflite"
    }
}
