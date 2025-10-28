package com.kotopogoda.uploader.feature.viewer.enhance

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.ExifInterface
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Центральный класс, отвечающий за вычисление метрик изображения и применение цепочки улучшений.
 * Алгоритм держится в JVM-памяти (через [ImageBuffer]) и поэтому пригоден как для unit-тестов,
 * так и для боевого использования через стандартные [Bitmap]-ы.
 */
class EnhanceEngine(
    private val decoder: ImageDecoder = BitmapImageDecoder(),
    private val encoder: ImageEncoder = BitmapImageEncoder(),
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    data class Request(
        val source: File,
        val strength: Float,
        val tileSize: Int = DEFAULT_TILE_SIZE,
        val exif: ExifInterface? = null,
        val outputFile: File? = null,
        val onTileProgress: (tileIndex: Int, total: Int, progress: Float) -> Unit = { _, _, _ -> },
    )

    data class Result(
        val file: File,
        val metrics: Metrics,
        val profile: Profile,
    )

    suspend fun enhance(request: Request): Result = withContext(dispatcher) {
        val strength = request.strength.coerceIn(0f, 1f)
        val buffer = decoder.decode(request.source)
        val metrics = MetricsCalculator.calculate(buffer)
        val profile = ProfileCalculator.calculate(metrics, strength)

        val ldcApplied = applyLdcBlocks(buffer.copy(), profile)
        val restormed = applyRestormer(ldcApplied, profile.restormerMix, request.tileSize, request.onTileProgress)
        val sharpened = applyEdgeAwareSharpen(restormed, profile.sharpenGain)
        val saturated = applySaturation(sharpened, profile.saturationGain)

        val output = request.outputFile ?: buildOutputFile(request.source)
        encoder.encode(saturated, output)
        tryCopyExif(request.exif, request.source, output)

        Result(
            file = output,
            metrics = metrics,
            profile = profile,
        )
    }

    private fun applyLdcBlocks(source: ImageBuffer, profile: Profile): ImageBuffer {
        val resultPixels = source.pixels.copyOf()
        val luminanceGain = profile.luminanceGain
        val darkBoost = profile.darkBoost
        val contrastGain = profile.contrastGain

        for (index in resultPixels.indices) {
            val color = resultPixels[index]
            val r = Color.red(color) / 255f
            val g = Color.green(color) / 255f
            val b = Color.blue(color) / 255f
            val luma = luminance(r, g, b)

            val lifted = clamp01(luma * luminanceGain + darkBoost * (1f - luma))
            val contrasted = applyContrast(lifted, contrastGain)
            val ratio = if (luma <= 1e-4f) 0f else contrasted / luma
            val newR = clamp01(r * ratio)
            val newG = clamp01(g * ratio)
            val newB = clamp01(b * ratio)
            resultPixels[index] = composeColor(Color.alpha(color), newR, newG, newB)
        }

        return ImageBuffer(source.width, source.height, resultPixels)
    }

    private fun applyRestormer(
        buffer: ImageBuffer,
        mix: Float,
        tileSize: Int,
        onTileProgress: (tileIndex: Int, total: Int, progress: Float) -> Unit,
    ): ImageBuffer {
        if (mix <= 1e-4f) {
            val totalTiles = computeTileCount(buffer.width, buffer.height, tileSize)
            repeat(totalTiles) { index -> onTileProgress(index, totalTiles, 1f) }
            return buffer
        }
        val width = buffer.width
        val height = buffer.height
        val pixels = buffer.pixels
        val result = pixels.copyOf()

        val xTiles = ((width + tileSize - 1) / tileSize)
        val yTiles = ((height + tileSize - 1) / tileSize)
        val totalTiles = xTiles * yTiles
        var tileIndex = 0
        for (ty in 0 until yTiles) {
            val yStart = ty * tileSize
            val yEnd = min(height, yStart + tileSize)
            for (tx in 0 until xTiles) {
                val xStart = tx * tileSize
                val xEnd = min(width, xStart + tileSize)
                onTileProgress(tileIndex, totalTiles, 0f)
                applyRestormerTile(
                    pixels = pixels,
                    result = result,
                    width = width,
                    height = height,
                    xStart = xStart,
                    xEnd = xEnd,
                    yStart = yStart,
                    yEnd = yEnd,
                    mix = mix,
                )
                onTileProgress(tileIndex, totalTiles, 1f)
                tileIndex++
            }
        }
        return ImageBuffer(width, height, result)
    }

    private fun applyRestormerTile(
        pixels: IntArray,
        result: IntArray,
        width: Int,
        height: Int,
        xStart: Int,
        xEnd: Int,
        yStart: Int,
        yEnd: Int,
        mix: Float,
    ) {
        val kernelRadius = 1
        for (y in yStart until yEnd) {
            val yBase = y * width
            for (x in xStart until xEnd) {
                val index = yBase + x
                val original = pixels[index]
                var rAcc = 0f
                var gAcc = 0f
                var bAcc = 0f
                var weightAcc = 0f
                for (ky in -kernelRadius..kernelRadius) {
                    val cy = clamp(y + ky, 0, height - 1)
                    val cBase = cy * width
                    for (kx in -kernelRadius..kernelRadius) {
                        val cx = clamp(x + kx, 0, width - 1)
                        val cIndex = cBase + cx
                        val neighbor = pixels[cIndex]
                        val weight = RESTORMER_KERNEL[kernelRadius + ky][kernelRadius + kx]
                        rAcc += Color.red(neighbor) * weight
                        gAcc += Color.green(neighbor) * weight
                        bAcc += Color.blue(neighbor) * weight
                        weightAcc += weight
                    }
                }
                val invWeight = if (weightAcc == 0f) 0f else 1f / weightAcc
                val smoothR = rAcc * invWeight / 255f
                val smoothG = gAcc * invWeight / 255f
                val smoothB = bAcc * invWeight / 255f

                val oR = Color.red(original) / 255f
                val oG = Color.green(original) / 255f
                val oB = Color.blue(original) / 255f

                val newR = clamp01(oR * (1f - mix) + smoothR * mix)
                val newG = clamp01(oG * (1f - mix) + smoothG * mix)
                val newB = clamp01(oB * (1f - mix) + smoothB * mix)
                result[index] = composeColor(Color.alpha(original), newR, newG, newB)
            }
        }
    }

    private fun applyEdgeAwareSharpen(buffer: ImageBuffer, gain: Float): ImageBuffer {
        if (gain <= 1f + 1e-3f) {
            return buffer
        }
        val width = buffer.width
        val height = buffer.height
        val pixels = buffer.pixels
        val result = pixels.copyOf()
        val kernelRadius = 1
        for (y in 0 until height) {
            val base = y * width
            for (x in 0 until width) {
                val index = base + x
                val original = pixels[index]
                val luma = luminance(
                    Color.red(original) / 255f,
                    Color.green(original) / 255f,
                    Color.blue(original) / 255f,
                )
                var edgeMagnitude = 0f
                for (ky in -kernelRadius..kernelRadius) {
                    val cy = clamp(y + ky, 0, height - 1)
                    val cBase = cy * width
                    for (kx in -kernelRadius..kernelRadius) {
                        val cx = clamp(x + kx, 0, width - 1)
                        if (cx == x && cy == y) continue
                        val neighbor = pixels[cBase + cx]
                        val neighborLuma = luminance(
                            Color.red(neighbor) / 255f,
                            Color.green(neighbor) / 255f,
                            Color.blue(neighbor) / 255f,
                        )
                        edgeMagnitude = max(edgeMagnitude, abs(luma - neighborLuma))
                    }
                }
                val edgeWeight = clamp01(edgeMagnitude * EDGE_GAIN_SCALE)
                val blurred = boxBlurPixel(pixels, width, height, x, y)
                val sharpR = Color.red(original) / 255f + (Color.red(original) - blurred.first) / 255f * (gain - 1f)
                val sharpG = Color.green(original) / 255f + (Color.green(original) - blurred.second) / 255f * (gain - 1f)
                val sharpB = Color.blue(original) / 255f + (Color.blue(original) - blurred.third) / 255f * (gain - 1f)

                val mix = edgeWeight
                val newR = clamp01(Color.red(original) / 255f * (1f - mix) + clamp01(sharpR) * mix)
                val newG = clamp01(Color.green(original) / 255f * (1f - mix) + clamp01(sharpG) * mix)
                val newB = clamp01(Color.blue(original) / 255f * (1f - mix) + clamp01(sharpB) * mix)
                result[index] = composeColor(Color.alpha(original), newR, newG, newB)
            }
        }
        return ImageBuffer(width, height, result)
    }

    private fun applySaturation(buffer: ImageBuffer, gain: Float): ImageBuffer {
        if (abs(gain - 1f) <= 1e-3f) {
            return buffer
        }
        val pixels = buffer.pixels.copyOf()
        for (index in pixels.indices) {
            val color = pixels[index]
            val hsv = rgbToHsv(
                Color.red(color) / 255f,
                Color.green(color) / 255f,
                Color.blue(color) / 255f,
            )
            val newS = clamp01(hsv.second * gain)
            val rgb = hsvToRgb(hsv.first, newS, hsv.third)
            pixels[index] = composeColor(Color.alpha(color), rgb.first, rgb.second, rgb.third)
        }
        return ImageBuffer(buffer.width, buffer.height, pixels)
    }

    private fun tryCopyExif(exif: ExifInterface?, source: File, output: File) {
        val sourceExif = exif ?: runCatching { ExifInterface(source) }.getOrNull()
        if (sourceExif == null) {
            return
        }
        runCatching {
            val tags = EXIF_TAGS
            val outputExif = ExifInterface(output)
            for (tag in tags) {
                val value = sourceExif.getAttribute(tag)
                if (value != null) {
                    outputExif.setAttribute(tag, value)
                }
            }
            outputExif.saveAttributes()
        }
    }

    interface ImageDecoder {
        fun decode(file: File): ImageBuffer
    }

    interface ImageEncoder {
        fun encode(buffer: ImageBuffer, target: File)
    }

    data class ImageBuffer(
        val width: Int,
        val height: Int,
        val pixels: IntArray,
    ) {
        init {
            require(pixels.size == width * height) {
                "Invalid pixel array size: ${pixels.size}, expected ${width * height}"
            }
        }

        fun copy(): ImageBuffer = ImageBuffer(width, height, pixels.copyOf())
    }

    data class Metrics(
        val lMean: Double,
        val pDark: Double,
        val bSharpness: Double,
        val nNoise: Double,
    )

    data class Profile(
        val luminanceGain: Float,
        val darkBoost: Float,
        val contrastGain: Float,
        val restormerMix: Float,
        val sharpenGain: Float,
        val saturationGain: Float,
    )

    internal object MetricsCalculator {
        fun calculate(buffer: ImageBuffer): Metrics {
            val width = buffer.width
            val height = buffer.height
            val pixels = buffer.pixels
            var luminanceSum = 0.0
            var darkCount = 0
            var gradientSum = 0.0
            var noiseSum = 0.0
            val total = pixels.size

            val luminances = DoubleArray(total)
            for (index in 0 until total) {
                val color = pixels[index]
                val r = Color.red(color) / 255.0
                val g = Color.green(color) / 255.0
                val b = Color.blue(color) / 255.0
                val luma = luminance(r, g, b)
                luminances[index] = luma
                luminanceSum += luma
                if (luma < DARK_THRESHOLD) darkCount++
            }

            for (y in 0 until height) {
                val base = y * width
                for (x in 0 until width) {
                    val index = base + x
                    val center = luminances[index]
                    val right = luminances[base + min(x + 1, width - 1)]
                    val down = luminances[min((y + 1) * width + x, total - 1)]
                    val gradX = right - center
                    val gradY = down - center
                    gradientSum += sqrt(gradX * gradX + gradY * gradY)

                    var localSum = 0.0
                    var count = 0
                    for (ky in -1..1) {
                        val cy = clamp(y + ky, 0, height - 1)
                        val cBase = cy * width
                        for (kx in -1..1) {
                            val cx = clamp(x + kx, 0, width - 1)
                            localSum += luminances[cBase + cx]
                            count++
                        }
                    }
                    val localMean = localSum / count
                    noiseSum += abs(center - localMean)
                }
            }

            val normFactor = total.toDouble()
            val lMean = if (normFactor == 0.0) 0.0 else luminanceSum / normFactor
            val pDark = if (normFactor == 0.0) 0.0 else darkCount / normFactor
            val bSharpness = (gradientSum / normFactor).coerceIn(0.0, 1.0)
            val nNoise = (noiseSum / normFactor * NOISE_SCALE).coerceIn(0.0, 1.0)
            return Metrics(lMean, pDark, bSharpness, nNoise)
        }
    }

    internal object ProfileCalculator {
        fun calculate(metrics: Metrics, strength: Float): Profile {
            val eased = easeInOut(strength)
            val luminanceGain = (1f + ((0.55f - metrics.lMean.toFloat()) * eased)).coerceIn(0.8f, 1.4f)
            val darkBoost = (metrics.pDark.toFloat() * (0.3f + 0.7f * eased)).coerceIn(0f, 0.6f)
            val contrastGain = (1f + ((0.5f - metrics.bSharpness.toFloat()) * 0.8f * eased)).coerceIn(0.8f, 1.5f)
            val restormerMix = (metrics.nNoise.toFloat() * eased).coerceIn(0f, 0.85f)
            val sharpenGain = (1f + (0.4f * (1f - restormerMix) * eased)).coerceIn(1f, 1.6f)
            val saturationGain = (1f + ((0.5f - metrics.lMean.toFloat()) * 0.3f * eased)).coerceIn(0.9f, 1.4f)
            return Profile(
                luminanceGain = luminanceGain,
                darkBoost = darkBoost,
                contrastGain = contrastGain,
                restormerMix = restormerMix,
                sharpenGain = sharpenGain,
                saturationGain = saturationGain,
            )
        }

        private fun easeInOut(value: Float): Float {
            val clamped = value.coerceIn(0f, 1f)
            return (clamped * clamped * (3 - 2 * clamped))
        }
    }

    class BitmapImageDecoder : ImageDecoder {
        override fun decode(file: File): ImageBuffer {
            val bitmap = BitmapFactory.decodeFile(file.absolutePath)
                ?: throw IOException("Не удалось декодировать ${file.absolutePath}")
            val width = bitmap.width
            val height = bitmap.height
            val pixels = IntArray(width * height)
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
            bitmap.recycle()
            return ImageBuffer(width, height, pixels)
        }
    }

    class BitmapImageEncoder : ImageEncoder {
        override fun encode(buffer: ImageBuffer, target: File) {
            val bitmap = Bitmap.createBitmap(buffer.width, buffer.height, Bitmap.Config.ARGB_8888)
            bitmap.setPixels(buffer.pixels, 0, buffer.width, 0, 0, buffer.width, buffer.height)
            FileOutputStream(target).use { stream ->
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, OUTPUT_JPEG_QUALITY, stream)) {
                    throw IOException("Не удалось сохранить JPEG ${target.absolutePath}")
                }
            }
            bitmap.recycle()
        }
    }

    companion object {
        private const val DEFAULT_TILE_SIZE = 256
        private const val OUTPUT_JPEG_QUALITY = 92
        private const val DARK_THRESHOLD = 0.25
        private const val NOISE_SCALE = 4.0
        private const val EDGE_GAIN_SCALE = 4f

        private val RESTORMER_KERNEL = arrayOf(
            floatArrayOf(1f, 2f, 1f),
            floatArrayOf(2f, 4f, 2f),
            floatArrayOf(1f, 2f, 1f),
        )

        private val EXIF_TAGS = arrayOf(
            ExifInterface.TAG_DATETIME,
            ExifInterface.TAG_MAKE,
            ExifInterface.TAG_MODEL,
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.TAG_FLASH,
            ExifInterface.TAG_FOCAL_LENGTH,
            ExifInterface.TAG_WHITE_BALANCE,
            ExifInterface.TAG_GPS_LATITUDE,
            ExifInterface.TAG_GPS_LONGITUDE,
            ExifInterface.TAG_GPS_ALTITUDE,
            ExifInterface.TAG_GPS_TIMESTAMP,
            ExifInterface.TAG_GPS_DATESTAMP,
            ExifInterface.TAG_EXPOSURE_TIME,
            ExifInterface.TAG_APERTURE,
            ExifInterface.TAG_ISO_SPEED_RATINGS,
        )
    }
}

private fun luminance(r: Double, g: Double, b: Double): Double = 0.2126 * r + 0.7152 * g + 0.0722 * b

private fun luminance(r: Float, g: Float, b: Float): Float = (0.2126f * r + 0.7152f * g + 0.0722f * b)

private fun clamp01(value: Float): Float = when {
    value < 0f -> 0f
    value > 1f -> 1f
    else -> value
}

private fun applyContrast(luminance: Float, gain: Float): Float {
    val centered = luminance - 0.5f
    return clamp01(centered * gain + 0.5f)
}

private fun composeColor(alpha: Int, r: Float, g: Float, b: Float): Int {
    val rr = (r * 255f).roundToInt().coerceIn(0, 255)
    val gg = (g * 255f).roundToInt().coerceIn(0, 255)
    val bb = (b * 255f).roundToInt().coerceIn(0, 255)
    return Color.argb(alpha, rr, gg, bb)
}

private fun boxBlurPixel(pixels: IntArray, width: Int, height: Int, x: Int, y: Int): Triple<Int, Int, Int> {
    var r = 0
    var g = 0
    var b = 0
    var count = 0
    for (ky in -1..1) {
        val cy = clamp(y + ky, 0, height - 1)
        val base = cy * width
        for (kx in -1..1) {
            val cx = clamp(x + kx, 0, width - 1)
            val color = pixels[base + cx]
            r += Color.red(color)
            g += Color.green(color)
            b += Color.blue(color)
            count++
        }
    }
    return Triple(r / count, g / count, b / count)
}

private fun clamp(value: Int, minValue: Int, maxValue: Int): Int = when {
    value < minValue -> minValue
    value > maxValue -> maxValue
    else -> value
}

private fun computeTileCount(width: Int, height: Int, tileSize: Int): Int {
    if (tileSize <= 0) return 0
    val xTiles = (width + tileSize - 1) / tileSize
    val yTiles = (height + tileSize - 1) / tileSize
    return xTiles * yTiles
}

private fun rgbToHsv(r: Float, g: Float, b: Float): Triple<Float, Float, Float> {
    val maxValue = max(r, max(g, b))
    val minValue = min(r, min(g, b))
    val delta = maxValue - minValue
    val hue = when {
        delta == 0f -> 0f
        maxValue == r -> ((g - b) / delta) % 6f
        maxValue == g -> ((b - r) / delta) + 2f
        else -> ((r - g) / delta) + 4f
    } * 60f
    val saturation = if (maxValue == 0f) 0f else delta / maxValue
    val value = maxValue
    val normalizedHue = if (hue < 0f) hue + 360f else hue
    return Triple(normalizedHue, saturation, value)
}

private fun hsvToRgb(h: Float, s: Float, v: Float): Triple<Float, Float, Float> {
    if (s == 0f) {
        return Triple(v, v, v)
    }
    val sector = (h / 60f) % 6f
    val i = sector.toInt()
    val f = sector - i
    val p = v * (1f - s)
    val q = v * (1f - s * f)
    val t = v * (1f - s * (1f - f))
    return when (i) {
        0 -> Triple(v, t, p)
        1 -> Triple(q, v, p)
        2 -> Triple(p, v, t)
        3 -> Triple(p, q, v)
        4 -> Triple(t, p, v)
        else -> Triple(v, p, q)
    }
}

private fun buildOutputFile(source: File): File {
    val parent = source.parentFile ?: source.absoluteFile.parentFile
    val name = source.nameWithoutExtension
    val suffix = source.extension.ifEmpty { "jpg" }
    val outputName = "${name}_enhanced.$suffix"
    return File(parent, outputName)
}
