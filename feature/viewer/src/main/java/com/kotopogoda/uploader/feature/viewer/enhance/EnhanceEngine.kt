package com.kotopogoda.uploader.feature.viewer.enhance

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.ExifInterface
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
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
    private val zeroDce: ZeroDceModel? = null,
    private val restormer: RestormerModel? = null,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    enum class Delegate {
        CPU,
        GPU,
    }

    data class Request(
        val source: File,
        val strength: Float,
        val tileSize: Int = DEFAULT_TILE_SIZE,
        val overlap: Int = DEFAULT_TILE_OVERLAP,
        val delegate: Delegate = Delegate.CPU,
        val exif: ExifInterface? = null,
        val outputFile: File? = null,
        val zeroDceIterations: Int = DEFAULT_ZERO_DCE_ITERATIONS,
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

        val zeroDceApplied = applyZeroDce(buffer.copy(), profile, request.delegate, request.zeroDceIterations)
        val restormed = applyRestormer(
            buffer = zeroDceApplied,
            mix = profile.restormerMix,
            tileSize = request.tileSize,
            overlap = request.overlap,
            delegate = request.delegate,
            onTileProgress = request.onTileProgress,
        )
        val detailed = applyDetailBoost(restormed, profile.alphaDetail)
        val sharpened = applyPostSharpen(detailed, profile.postSharpen)
        val saturated = applyVibranceAndSaturation(sharpened, profile.vibranceGain, profile.saturationGain)

        val output = request.outputFile ?: buildOutputFile(request.source)
        encoder.encode(saturated, output)
        tryCopyExif(request.exif, request.source, output)

        Result(
            file = output,
            metrics = metrics,
            profile = profile,
        )
    }

    private suspend fun applyZeroDce(
        source: ImageBuffer,
        profile: Profile,
        delegate: Delegate,
        iterations: Int,
    ): ImageBuffer {
        val mix = profile.kDce
        if (mix <= 1e-3f) {
            return source
        }
        val safeIterations = max(1, iterations)
        val processed = zeroDce?.enhance(source.copy(), delegate, safeIterations)
            ?: fallbackZeroDce(source.copy(), safeIterations)
        return blendBuffers(source, processed, mix)
    }

    private suspend fun applyRestormer(
        buffer: ImageBuffer,
        mix: Float,
        tileSize: Int,
        overlap: Int,
        delegate: Delegate,
        onTileProgress: (tileIndex: Int, total: Int, progress: Float) -> Unit,
    ): ImageBuffer {
        val model = restormer
        if (mix <= 1e-3f || model == null) {
            val totalTiles = computeTileCount(buffer.width, buffer.height, tileSize)
            repeat(totalTiles) { onTileProgress(it, totalTiles, 1f) }
            return buffer
        }
        val safeTile = if (tileSize <= 0) DEFAULT_TILE_SIZE else tileSize
        val step = max(1, safeTile - overlap * 2)
        val width = buffer.width
        val height = buffer.height
        val tilesX = ceil(width / step.toDouble()).toInt()
        val tilesY = ceil(height / step.toDouble()).toInt()
        val totalTiles = max(1, tilesX * tilesY)
        val accR = FloatArray(width * height)
        val accG = FloatArray(width * height)
        val accB = FloatArray(width * height)
        val accWeight = FloatArray(width * height)
        var index = 0
        for (ty in 0 until tilesY) {
            val innerY = ty * step
            val innerHeight = min(safeTile, height - innerY)
            if (innerHeight <= 0) continue
            for (tx in 0 until tilesX) {
                val innerX = tx * step
                val innerWidth = min(safeTile, width - innerX)
                if (innerWidth <= 0) continue
                onTileProgress(index, totalTiles, 0f)
                val tile = buffer.subRegion(innerX, innerY, innerWidth, innerHeight)
                val processed = model.denoise(tile, delegate)
                val tilePixels = processed.pixels
                val tileWidth = processed.width
                val tileHeight = processed.height
                for (py in 0 until tileHeight) {
                    val globalY = innerY + py
                    if (globalY >= height) continue
                    val weightY = featherWeight(py, tileHeight, overlap)
                    val base = globalY * width
                    val tileBase = py * tileWidth
                    for (px in 0 until tileWidth) {
                        val globalX = innerX + px
                        if (globalX >= width) continue
                        val weightX = featherWeight(px, tileWidth, overlap)
                        val weight = weightX * weightY
                        if (weight <= 0f) continue
                        val color = tilePixels[tileBase + px]
                        val idx = base + globalX
                        accR[idx] += Color.red(color) * weight
                        accG[idx] += Color.green(color) * weight
                        accB[idx] += Color.blue(color) * weight
                        accWeight[idx] += weight
                    }
                }
                onTileProgress(index, totalTiles, 1f)
                index++
            }
        }
        val resultPixels = buffer.pixels.copyOf()
        for (i in resultPixels.indices) {
            val baseColor = buffer.pixels[i]
            val weight = accWeight[i]
            val processedR = if (weight > 0f) accR[i] / weight / 255f else Color.red(baseColor) / 255f
            val processedG = if (weight > 0f) accG[i] / weight / 255f else Color.green(baseColor) / 255f
            val processedB = if (weight > 0f) accB[i] / weight / 255f else Color.blue(baseColor) / 255f
            val originalR = Color.red(baseColor) / 255f
            val originalG = Color.green(baseColor) / 255f
            val originalB = Color.blue(baseColor) / 255f
            val newR = clamp01(originalR * (1f - mix) + processedR * mix)
            val newG = clamp01(originalG * (1f - mix) + processedG * mix)
            val newB = clamp01(originalB * (1f - mix) + processedB * mix)
            resultPixels[i] = composeColor(Color.alpha(baseColor), newR, newG, newB)
        }
        return ImageBuffer(buffer.width, buffer.height, resultPixels)
    }

    private fun applyDetailBoost(buffer: ImageBuffer, alphaDetail: Float): ImageBuffer {
        if (alphaDetail <= 1f + 1e-3f) {
            return buffer
        }
        val width = buffer.width
        val height = buffer.height
        val source = buffer.pixels
        val result = source.copyOf()
        for (y in 0 until height) {
            val base = y * width
            for (x in 0 until width) {
                val index = base + x
                val (blurR, blurG, blurB) = boxBlurPixel(source, width, height, x, y)
                val original = source[index]
                val boost = alphaDetail - 1f
                val newR = clamp01(Color.red(original) / 255f + (Color.red(original) - blurR) / 255f * boost)
                val newG = clamp01(Color.green(original) / 255f + (Color.green(original) - blurG) / 255f * boost)
                val newB = clamp01(Color.blue(original) / 255f + (Color.blue(original) - blurB) / 255f * boost)
                result[index] = composeColor(Color.alpha(original), newR, newG, newB)
            }
        }
        return ImageBuffer(width, height, result)
    }

    private fun applyPostSharpen(buffer: ImageBuffer, amount: Float): ImageBuffer {
        if (amount <= 1e-3f) {
            return buffer
        }
        val width = buffer.width
        val height = buffer.height
        val pixels = buffer.pixels
        val result = pixels.copyOf()
        for (y in 0 until height) {
            val base = y * width
            for (x in 0 until width) {
                val index = base + x
                val original = pixels[index]
                val laplace = laplacianPixel(pixels, width, height, x, y)
                val r = clamp01(Color.red(original) / 255f + laplace.first * amount)
                val g = clamp01(Color.green(original) / 255f + laplace.second * amount)
                val b = clamp01(Color.blue(original) / 255f + laplace.third * amount)
                result[index] = composeColor(Color.alpha(original), r, g, b)
            }
        }
        return ImageBuffer(width, height, result)
    }

    private fun applyVibranceAndSaturation(
        buffer: ImageBuffer,
        vibranceGain: Float,
        saturationGain: Float,
    ): ImageBuffer {
        if (abs(vibranceGain) <= 1e-3f && abs(saturationGain - 1f) <= 1e-3f) {
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
            val vibrance = clamp01(hsv.second + (1f - hsv.second) * vibranceGain)
            val saturation = clamp01(vibrance * saturationGain)
            val rgb = hsvToRgb(hsv.first, saturation, hsv.third)
            pixels[index] = composeColor(Color.alpha(color), rgb.first, rgb.second, rgb.third)
        }
        return ImageBuffer(buffer.width, buffer.height, pixels)
    }

    private fun blendBuffers(a: ImageBuffer, b: ImageBuffer, mix: Float): ImageBuffer {
        val amount = mix.coerceIn(0f, 1f)
        if (amount <= 1e-3f) return a
        val width = a.width
        val height = a.height
        require(width == b.width && height == b.height) { "Buffers must have the same size" }
        val result = IntArray(width * height)
        for (i in result.indices) {
            val colorA = a.pixels[i]
            val colorB = b.pixels[i]
            val r = clamp01(Color.red(colorA) / 255f * (1f - amount) + Color.red(colorB) / 255f * amount)
            val g = clamp01(Color.green(colorA) / 255f * (1f - amount) + Color.green(colorB) / 255f * amount)
            val bChan = clamp01(Color.blue(colorA) / 255f * (1f - amount) + Color.blue(colorB) / 255f * amount)
            result[i] = composeColor(Color.alpha(colorA), r, g, bChan)
        }
        return ImageBuffer(width, height, result)
    }

    private fun fallbackZeroDce(buffer: ImageBuffer, iterations: Int): ImageBuffer {
        val pixels = buffer.pixels.copyOf()
        val gain = 0.08f * iterations
        for (i in pixels.indices) {
            val color = pixels[i]
            val r = clamp01(Color.red(color) / 255f + gain * (1f - Color.red(color) / 255f))
            val g = clamp01(Color.green(color) / 255f + gain * (1f - Color.green(color) / 255f))
            val b = clamp01(Color.blue(color) / 255f + gain * (1f - Color.blue(color) / 255f))
            pixels[i] = composeColor(Color.alpha(color), r, g, b)
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

    interface ZeroDceModel {
        suspend fun enhance(buffer: ImageBuffer, delegate: Delegate, iterations: Int): ImageBuffer
    }

    interface RestormerModel {
        suspend fun denoise(tile: ImageBuffer, delegate: Delegate): ImageBuffer
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

        fun subRegion(x: Int, y: Int, regionWidth: Int, regionHeight: Int): ImageBuffer {
            val safeWidth = min(regionWidth, width)
            val safeHeight = min(regionHeight, height)
            val pixels = IntArray(safeWidth * safeHeight)
            for (py in 0 until safeHeight) {
                val srcY = min(height - 1, y + py)
                val srcBase = srcY * width
                val dstBase = py * safeWidth
                for (px in 0 until safeWidth) {
                    val srcX = min(width - 1, x + px)
                    pixels[dstBase + px] = this.pixels[srcBase + srcX]
                }
            }
            return ImageBuffer(safeWidth, safeHeight, pixels)
        }
    }

    data class Metrics(
        val lMean: Double,
        val pDark: Double,
        val bSharpness: Double,
        val nNoise: Double,
    )

    data class Profile(
        val kDce: Float,
        val restormerMix: Float,
        val alphaDetail: Float,
        val postSharpen: Float,
        val vibranceGain: Float,
        val saturationGain: Float,
    )

    internal object MetricsCalculator {
        fun calculate(buffer: ImageBuffer): Metrics {
            val width = buffer.width
            val height = buffer.height
            val pixels = buffer.pixels
            val total = pixels.size
            if (total == 0) return Metrics(0.0, 0.0, 0.0, 0.0)

            val luminances = DoubleArray(total)
            var luminanceSum = 0.0
            var darkCount = 0
            for (index in 0 until total) {
                val color = pixels[index]
                val r = Color.red(color) / 255.0
                val g = Color.green(color) / 255.0
                val b = Color.blue(color) / 255.0
                val luma = luminance(r, g, b)
                luminances[index] = luma
                luminanceSum += luma
                if (luma < DARK_LUMINANCE_THRESHOLD) {
                    darkCount++
                }
            }

            var gradientSum = 0.0
            var noiseSum = 0.0
            for (y in 0 until height) {
                val base = y * width
                for (x in 0 until width) {
                    val index = base + x
                    val sobel = sobelAt(luminances, width, height, x, y)
                    gradientSum += sobel
                    val blurred = gaussianBlurAt(luminances, width, height, x, y)
                    noiseSum += abs(luminances[index] - blurred)
                }
            }

            val norm = total.toDouble()
            val lMean = luminanceSum / norm
            val pDark = darkCount / norm
            val bSharpness = (gradientSum / norm / SOBEL_NORMALIZATION).coerceIn(0.0, 1.0)
            val nNoise = (noiseSum / norm / NOISE_NORMALIZATION).coerceIn(0.0, 1.0)
            return Metrics(lMean, pDark, bSharpness, nNoise)
        }
    }

    internal object ProfileCalculator {
        fun calculate(metrics: Metrics, strength: Float): Profile {
            val eased = easeInOut(strength.coerceIn(0f, 1f))
            val lowLight = ((LOW_LIGHT_TARGET - metrics.lMean).coerceAtLeast(0.0) / LOW_LIGHT_TARGET) * 0.7 +
                (metrics.pDark / DARK_PORTION_TARGET).coerceAtMost(1.0) * 0.3
            val kDce = (lowLight * eased).toFloat().coerceIn(0f, 1f)

            val restormerMix = (metrics.nNoise * eased).toFloat().coerceIn(0f, 1f)

            val detailDeficit = (DETAIL_TARGET - metrics.bSharpness).coerceAtLeast(0.0)
            val alphaDetail = (1f + detailDeficit.toFloat() * (1f - metrics.nNoise.toFloat()) * eased * 0.9f)
                .coerceIn(1f, 1.8f)

            val postSharpen = ((0.15f + 0.55f * (1f - metrics.nNoise.toFloat())) * eased)
                .coerceIn(0f, 0.7f)

            val vibranceGain = ((0.2f + 0.6f * (LOW_LIGHT_TARGET - metrics.lMean).toFloat().coerceAtLeast(0f)) * eased)
                .coerceIn(0f, 0.8f)

            val saturationGain = (1f + ((0.55f - metrics.lMean.toFloat()) * eased * 0.6f))
                .coerceIn(0.85f, 1.4f)

            return Profile(
                kDce = kDce,
                restormerMix = restormerMix,
                alphaDetail = alphaDetail,
                postSharpen = postSharpen,
                vibranceGain = vibranceGain,
                saturationGain = saturationGain,
            )
        }

        private fun easeInOut(value: Float): Float {
            val clamped = value.coerceIn(0f, 1f)
            return clamped * clamped * (3 - 2 * clamped)
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
        private const val DEFAULT_TILE_SIZE = 384
        private const val DEFAULT_TILE_OVERLAP = 32
        private const val DEFAULT_ZERO_DCE_ITERATIONS = 8
        private const val OUTPUT_JPEG_QUALITY = 92
        private const val DARK_LUMINANCE_THRESHOLD = 0.22
        private const val LOW_LIGHT_TARGET = 0.45
        private const val DARK_PORTION_TARGET = 0.6
        private const val DETAIL_TARGET = 0.55
        private const val SOBEL_NORMALIZATION = 4.5
        private const val NOISE_NORMALIZATION = 0.12

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

private fun luminance(r: Float, g: Float, b: Float): Float = 0.2126f * r + 0.7152f * g + 0.0722f * b

private fun clamp01(value: Float): Float = when {
    value < 0f -> 0f
    value > 1f -> 1f
    else -> value
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

private fun laplacianPixel(pixels: IntArray, width: Int, height: Int, x: Int, y: Int): Triple<Float, Float, Float> {
    val center = pixels[y * width + x]
    var r = -4 * Color.red(center)
    var g = -4 * Color.green(center)
    var b = -4 * Color.blue(center)
    val offsets = arrayOf(0 to -1, -1 to 0, 1 to 0, 0 to 1)
    for ((ox, oy) in offsets) {
        val cx = clamp(x + ox, 0, width - 1)
        val cy = clamp(y + oy, 0, height - 1)
        val color = pixels[cy * width + cx]
        r += Color.red(color)
        g += Color.green(color)
        b += Color.blue(color)
    }
    val scale = 1f / 255f
    return Triple(r * scale, g * scale, b * scale)
}

private fun clamp(value: Int, minValue: Int, maxValue: Int): Int = when {
    value < minValue -> minValue
    value > maxValue -> maxValue
    else -> value
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

private fun computeTileCount(width: Int, height: Int, tileSize: Int): Int {
    if (tileSize <= 0) return 0
    val xTiles = (width + tileSize - 1) / tileSize
    val yTiles = (height + tileSize - 1) / tileSize
    return xTiles * yTiles
}

private fun featherWeight(position: Int, size: Int, overlap: Int): Float {
    if (overlap <= 0 || size <= 1) return 1f
    val distanceToEdge = min(position, size - 1 - position).toFloat()
    return clamp01((distanceToEdge / overlap).coerceIn(0f, 1f))
}

private fun sobelAt(luma: DoubleArray, width: Int, height: Int, x: Int, y: Int): Double {
    var gx = 0.0
    var gy = 0.0
    for (ky in -1..1) {
        val cy = clamp(y + ky, 0, height - 1)
        val base = cy * width
        for (kx in -1..1) {
            val cx = clamp(x + kx, 0, width - 1)
            val weightX = SOBEL_X[ky + 1][kx + 1]
            val weightY = SOBEL_Y[ky + 1][kx + 1]
            val value = luma[base + cx]
            gx += weightX * value
            gy += weightY * value
        }
    }
    return hypot(gx, gy)
}

private fun gaussianBlurAt(luma: DoubleArray, width: Int, height: Int, x: Int, y: Int): Double {
    var acc = 0.0
    var weightAcc = 0.0
    for (ky in -1..1) {
        val cy = clamp(y + ky, 0, height - 1)
        val base = cy * width
        for (kx in -1..1) {
            val cx = clamp(x + kx, 0, width - 1)
            val weight = GAUSSIAN_KERNEL[ky + 1][kx + 1]
            acc += luma[base + cx] * weight
            weightAcc += weight
        }
    }
    return if (weightAcc == 0.0) 0.0 else acc / weightAcc
}

private val SOBEL_X = arrayOf(
    doubleArrayOf(-1.0, 0.0, 1.0),
    doubleArrayOf(-2.0, 0.0, 2.0),
    doubleArrayOf(-1.0, 0.0, 1.0),
)

private val SOBEL_Y = arrayOf(
    doubleArrayOf(-1.0, -2.0, -1.0),
    doubleArrayOf(0.0, 0.0, 0.0),
    doubleArrayOf(1.0, 2.0, 1.0),
)

private val GAUSSIAN_KERNEL = arrayOf(
    doubleArrayOf(1.0, 2.0, 1.0),
    doubleArrayOf(2.0, 4.0, 2.0),
    doubleArrayOf(1.0, 2.0, 1.0),
)

private fun buildOutputFile(source: File): File {
    val parent = source.parentFile ?: source.absoluteFile.parentFile
    val name = source.nameWithoutExtension
    val suffix = source.extension.ifEmpty { "jpg" }
    val outputName = "${name}_enhanced.$suffix"
    return File(parent, outputName)
}
