package com.kotopogoda.uploader.feature.viewer.enhance.backend

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Half
import com.kotopogoda.uploader.feature.viewer.enhance.EnhanceEngine
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt
import org.tensorflow.lite.DataType

internal object TfliteImageOps {
    private const val CHANNEL_COUNT = 3

    data class PreparedImage(
        val floats: FloatArray,
        val width: Int,
        val height: Int,
        val originalWidth: Int,
        val originalHeight: Int,
    )

    fun prepareInput(
        buffer: EnhanceEngine.ImageBuffer,
        targetWidth: Int,
        targetHeight: Int,
    ): PreparedImage {
        require(targetWidth > 0 && targetHeight > 0) { "Invalid target size" }
        val originalWidth = buffer.width
        val originalHeight = buffer.height
        val baseBitmap = Bitmap.createBitmap(originalWidth, originalHeight, Bitmap.Config.ARGB_8888)
        baseBitmap.setPixels(buffer.pixels, 0, originalWidth, 0, 0, originalWidth, originalHeight)
        val workingBitmap = if (originalWidth != targetWidth || originalHeight != targetHeight) {
            Bitmap.createScaledBitmap(baseBitmap, targetWidth, targetHeight, true)
        } else {
            baseBitmap
        }
        val pixels = IntArray(targetWidth * targetHeight)
        workingBitmap.getPixels(pixels, 0, targetWidth, 0, 0, targetWidth, targetHeight)
        if (workingBitmap !== baseBitmap) {
            workingBitmap.recycle()
        }
        baseBitmap.recycle()
        val floats = FloatArray(targetWidth * targetHeight * CHANNEL_COUNT)
        var index = 0
        for (color in pixels) {
            floats[index++] = Color.red(color) / 255f
            floats[index++] = Color.green(color) / 255f
            floats[index++] = Color.blue(color) / 255f
        }
        return PreparedImage(
            floats = floats,
            width = targetWidth,
            height = targetHeight,
            originalWidth = originalWidth,
            originalHeight = originalHeight,
        )
    }

    fun allocateBuffer(dataType: DataType, elementCount: Int): ByteBuffer {
        val bytesPerElement = when (dataType) {
            DataType.FLOAT32 -> 4
            DataType.FLOAT16 -> 2
            DataType.UINT8 -> 1
            else -> throw IllegalArgumentException("Unsupported tensor type: $dataType")
        }
        return ByteBuffer.allocateDirect(elementCount * bytesPerElement).order(ByteOrder.nativeOrder())
    }

    fun writeToBuffer(source: FloatArray, buffer: ByteBuffer, dataType: DataType) {
        buffer.rewind()
        when (dataType) {
            DataType.FLOAT32 -> {
                for (value in source) {
                    buffer.putFloat(clamp01(value))
                }
            }
            DataType.FLOAT16 -> {
                for (value in source) {
                    buffer.putShort(Half.toHalf(clamp01(value)))
                }
            }
            DataType.UINT8 -> {
                for (value in source) {
                    val byteValue = (clamp01(value) * 255f + 0.5f).roundToInt().coerceIn(0, 255)
                    buffer.put(byteValue.toByte())
                }
            }
            else -> throw IllegalArgumentException("Unsupported tensor type: $dataType")
        }
    }

    fun readFromBuffer(buffer: ByteBuffer, dataType: DataType, target: FloatArray) {
        buffer.rewind()
        when (dataType) {
            DataType.FLOAT32 -> {
                for (index in target.indices) {
                    target[index] = buffer.float
                }
            }
            DataType.FLOAT16 -> {
                for (index in target.indices) {
                    target[index] = Half.toFloat(buffer.short)
                }
            }
            DataType.UINT8 -> {
                for (index in target.indices) {
                    val unsigned = buffer.get().toInt() and 0xFF
                    target[index] = unsigned / 255f
                }
            }
            else -> throw IllegalArgumentException("Unsupported tensor type: $dataType")
        }
    }

    fun buildImageBuffer(
        floats: FloatArray,
        width: Int,
        height: Int,
        originalWidth: Int,
        originalHeight: Int,
    ): EnhanceEngine.ImageBuffer {
        val pixels = IntArray(width * height)
        var index = 0
        for (i in 0 until width * height) {
            val r = clamp01(floats[index++])
            val g = clamp01(floats[index++])
            val b = clamp01(floats[index++])
            pixels[i] = Color.argb(
                255,
                (r * 255f + 0.5f).roundToInt().coerceIn(0, 255),
                (g * 255f + 0.5f).roundToInt().coerceIn(0, 255),
                (b * 255f + 0.5f).roundToInt().coerceIn(0, 255),
            )
        }
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        val finalBitmap = if (width != originalWidth || height != originalHeight) {
            Bitmap.createScaledBitmap(bitmap, originalWidth, originalHeight, true)
        } else {
            bitmap
        }
        val finalPixels = IntArray(originalWidth * originalHeight)
        finalBitmap.getPixels(finalPixels, 0, originalWidth, 0, 0, originalWidth, originalHeight)
        if (finalBitmap !== bitmap) {
            finalBitmap.recycle()
        }
        bitmap.recycle()
        return EnhanceEngine.ImageBuffer(originalWidth, originalHeight, finalPixels)
    }

    private fun clamp01(value: Float): Float = when {
        value < 0f -> 0f
        value > 1f -> 1f
        else -> value
    }
}
