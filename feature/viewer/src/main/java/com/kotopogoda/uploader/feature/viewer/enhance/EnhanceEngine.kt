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
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.system.measureTimeMillis
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

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

    enum class ModelBackend {
        TFLITE,
        NCNN,
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
        val delegate: Delegate,
        val pipeline: Pipeline,
        val timings: Timings,
        val models: ModelsTelemetry,
    )

    data class Pipeline(
        val stages: List<String> = emptyList(),
        val tileSize: Int = DEFAULT_TILE_SIZE,
        val overlap: Int = DEFAULT_TILE_OVERLAP,
        val tileCount: Int = 0,
        val zeroDceIterations: Int = 0,
        val zeroDceApplied: Boolean = false,
        val restormerMix: Float = 0f,
        val restormerApplied: Boolean = false,
        val hasSeamFix: Boolean = false,
    )

    data class Timings(
        val decode: Long = 0,
        val metrics: Long = 0,
        val zeroDce: Long = 0,
        val restormer: Long = 0,
        val blend: Long = 0,
        val sharpen: Long = 0,
        val vibrance: Long = 0,
        val encode: Long = 0,
        val exif: Long = 0,
        val total: Long = 0,
    )

    data class ModelResult(
        val buffer: ImageBuffer,
        val delegate: Delegate,
    )

    data class ModelUsage(
        val backend: ModelBackend,
        val checksum: String,
    )

    data class ModelsTelemetry(
        val zeroDce: ModelUsage?,
        val restormer: ModelUsage?,
    )

    suspend fun enhance(request: Request): Result = withContext(dispatcher) {
        val strength = request.strength.coerceIn(0f, 1f)

        zeroDce?.let {
            Timber.tag(LOG_TAG).i(
                "ZeroDCE model backend=%s checksum=%s",
                it.backend,
                it.checksum,
            )
        }
        restormer?.let {
            Timber.tag(LOG_TAG).i(
                "Restormer model backend=%s checksum=%s",
                it.backend,
                it.checksum,
            )
        }

        lateinit var buffer: ImageBuffer
        lateinit var metrics: Metrics
        lateinit var profile: Profile
        lateinit var zeroResult: ModelResult
        lateinit var restReport: RestormerReport
        lateinit var denoised: ImageBuffer
        lateinit var sharpened: ImageBuffer
        lateinit var saturated: ImageBuffer
        lateinit var output: File

        var actualDelegate = request.delegate
        var decodeDuration = 0L
        var metricsDuration = 0L
        var zeroDuration = 0L
        var restormerDuration = 0L
        var blendDuration = 0L
        var sharpenDuration = 0L
        var vibranceDuration = 0L
        var encodeDuration = 0L
        var exifDuration = 0L

        val totalDuration = measureTimeMillis {
            decodeDuration = measureTimeMillis {
                buffer = decoder.decode(request.source)
            }
            metricsDuration = measureTimeMillis {
                metrics = MetricsCalculator.calculate(buffer)
            }
            profile = ProfileCalculator.calculate(metrics, strength)

            zeroDuration = measureTimeMillis {
                zeroResult = applyZeroDce(buffer.copy(), profile, request.delegate, request.zeroDceIterations)
            }
            actualDelegate = zeroResult.delegate

            restormerDuration = measureTimeMillis {
                restReport = runRestormer(
                    buffer = zeroResult.buffer,
                    mix = profile.restormerMix,
                    tileSize = request.tileSize,
                    overlap = request.overlap,
                    delegate = zeroResult.delegate,
                    onTileProgress = request.onTileProgress,
                )
            }

            val restResult = restReport.result
            denoised = if (restResult != null && profile.alphaDetail > 1e-3f) {
                actualDelegate = restResult.delegate
                var blended: ImageBuffer
                blendDuration = measureTimeMillis {
                    blended = blendBuffers(zeroResult.buffer, restResult.buffer, profile.alphaDetail)
                }
                blended
            } else {
                zeroResult.buffer
            }

            sharpenDuration = measureTimeMillis {
                sharpened = applyEdgeAwareUnsharp(
                    denoised,
                    profile.sharpenAmount,
                    profile.sharpenRadius,
                    profile.sharpenThreshold,
                )
            }
            vibranceDuration = measureTimeMillis {
                saturated = applyVibranceAndSaturation(
                    sharpened,
                    profile.vibranceGain,
                    profile.saturationGain,
                )
            }

            output = request.outputFile ?: buildOutputFile(request.source)
            encodeDuration = measureTimeMillis {
                encoder.encode(saturated, output)
            }
            exifDuration = measureTimeMillis {
                tryCopyExif(request.exif, request.source, output)
            }
        }

        if (actualDelegate != request.delegate) {
            Timber.tag(LOG_TAG).i("Enhance delegate fallback: requested=%s actual=%s", request.delegate, actualDelegate)
        } else {
            Timber.tag(LOG_TAG).i("Enhance delegate: %s", actualDelegate)
        }

        val pipeline = buildPipeline(profile, request, restReport)
        val timings = Timings(
            decode = decodeDuration,
            metrics = metricsDuration,
            zeroDce = zeroDuration,
            restormer = restormerDuration,
            blend = blendDuration,
            sharpen = sharpenDuration,
            vibrance = vibranceDuration,
            encode = encodeDuration,
            exif = exifDuration,
            total = totalDuration,
        )

        val models = ModelsTelemetry(
            zeroDce = zeroDce?.let { ModelUsage(it.backend, it.checksum) },
            restormer = restormer?.let { ModelUsage(it.backend, it.checksum) },
        )

        Result(
            file = output,
            metrics = metrics,
            profile = profile,
            delegate = actualDelegate,
            pipeline = pipeline,
            timings = timings,
            models = models,
        )
    }

    private suspend fun applyZeroDce(
        source: ImageBuffer,
        profile: Profile,
        delegate: Delegate,
        iterations: Int,
    ): ModelResult {
        val mix = profile.kDce
        if (!profile.isLowLight || mix <= 1e-3f) {
            return ModelResult(source, delegate)
        }
        val safeIterations = max(1, iterations)
        val backend = zeroDce
        if (backend == null) {
            return ModelResult(fallbackZeroDce(source.copy(), safeIterations), Delegate.CPU)
        }
        val processed = try {
            backend.enhance(source.copy(), delegate, safeIterations)
        } catch (error: Exception) {
            Timber.tag(LOG_TAG).w(error, "ZeroDCE backend failed, using fallback curve")
            return ModelResult(fallbackZeroDce(source.copy(), safeIterations), Delegate.CPU)
        }
        val blended = blendBuffers(source, processed.buffer, mix)
        return ModelResult(blended, processed.delegate)
    }

    private data class RestormerReport(
        val result: ModelResult?,
        val delegate: Delegate,
        val tileCount: Int,
        val seamFixApplied: Boolean,
    )

    private suspend fun runRestormer(
        buffer: ImageBuffer,
        mix: Float,
        tileSize: Int,
        overlap: Int,
        delegate: Delegate,
        onTileProgress: (tileIndex: Int, total: Int, progress: Float) -> Unit,
    ): RestormerReport {
        val model = restormer
        if (mix <= 1e-3f || model == null) {
            val totalTiles = computeTileCount(buffer.width, buffer.height, tileSize)
            repeat(totalTiles) { onTileProgress(it, totalTiles, 1f) }
            return RestormerReport(
                result = null,
                delegate = delegate,
                tileCount = totalTiles,
                seamFixApplied = overlap > 0,
            )
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
        var currentDelegate = delegate
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
                val processed = try {
                    model.denoise(tile, currentDelegate)
                } catch (error: Exception) {
                    Timber.tag(LOG_TAG).w(
                        error,
                        "Restormer backend failed for tile (%d,%d), using original",
                        tx,
                        ty,
                    )
                    currentDelegate = Delegate.CPU
                    ModelResult(tile, Delegate.CPU)
                }
                currentDelegate = processed.delegate
                val tilePixels = processed.buffer.pixels
                val tileWidth = processed.buffer.width
                val tileHeight = processed.buffer.height
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
        val resultPixels = IntArray(buffer.pixels.size)
        for (i in resultPixels.indices) {
            val baseColor = buffer.pixels[i]
            val weight = accWeight[i]
            val processedR = if (weight > 0f) accR[i] / weight / 255f else Color.red(baseColor) / 255f
            val processedG = if (weight > 0f) accG[i] / weight / 255f else Color.green(baseColor) / 255f
            val processedB = if (weight > 0f) accB[i] / weight / 255f else Color.blue(baseColor) / 255f
            resultPixels[i] = composeColor(Color.alpha(baseColor), processedR, processedG, processedB)
        }
        return RestormerReport(
            result = ModelResult(ImageBuffer(buffer.width, buffer.height, resultPixels), currentDelegate),
            delegate = currentDelegate,
            tileCount = totalTiles,
            seamFixApplied = overlap > 0 && totalTiles > 0,
        )
    }

    private fun buildPipeline(profile: Profile, request: Request, restReport: RestormerReport): Pipeline {
        val stages = mutableListOf<String>()
        val zeroApplied = profile.isLowLight && profile.kDce > 1e-3f
        if (zeroApplied) {
            stages += "zero_dce"
        }
        val restormerUsed = restReport.result != null && profile.restormerMix > 1e-3f && profile.alphaDetail > 1e-3f
        if (restormerUsed) {
            stages += "restormer"
        }
        if (profile.sharpenAmount > 1e-3f && profile.sharpenRadius > 0.1f) {
            stages += "sharpen"
        }
        if (profile.vibranceGain > 1e-3f) {
            stages += "vibrance"
        }
        if (kotlin.math.abs(profile.saturationGain - 1f) > 1e-3f) {
            stages += "saturation"
        }
        val zeroIterations = if (zeroApplied) max(1, request.zeroDceIterations) else 0
        return Pipeline(
            stages = stages,
            tileSize = request.tileSize,
            overlap = request.overlap,
            tileCount = restReport.tileCount,
            zeroDceIterations = zeroIterations,
            zeroDceApplied = zeroApplied,
            restormerMix = profile.restormerMix,
            restormerApplied = restormerUsed,
            hasSeamFix = restReport.seamFixApplied && restormerUsed,
        )
    }

    private fun computeTileCount(width: Int, height: Int, tileSize: Int): Int {
        if (tileSize <= 0 || width <= 0 || height <= 0) {
            return 0
        }
        val tilesX = (width + tileSize - 1) / tileSize
        val tilesY = (height + tileSize - 1) / tileSize
        return tilesX * tilesY
    }

    private fun applyEdgeAwareUnsharp(
        buffer: ImageBuffer,
        amount: Float,
        radius: Float,
        threshold: Float,
    ): ImageBuffer {
        if (amount <= 1e-3f || radius <= 0.1f) {
            return buffer
        }
        val width = buffer.width
        val height = buffer.height
        val total = width * height
        if (total == 0) return buffer

        val srcR = FloatArray(total)
        val srcG = FloatArray(total)
        val srcB = FloatArray(total)
        val pixels = buffer.pixels
        for (i in 0 until total) {
            val color = pixels[i]
            srcR[i] = Color.red(color) / 255f
            srcG[i] = Color.green(color) / 255f
            srcB[i] = Color.blue(color) / 255f
        }

        val rad = radius.coerceIn(0.5f, 12f).roundToInt().coerceAtLeast(1)
        val (blurR, blurG, blurB) = gaussianBlur(srcR, srcG, srcB, width, height, rad)
        val sharpened = IntArray(total)
        val limit = amount.coerceIn(0f, 1.5f)
        val thr = threshold.coerceIn(0f, 1f)
        for (i in 0 until total) {
            val oR = srcR[i]
            val oG = srcG[i]
            val oB = srcB[i]
            val bR = blurR[i]
            val bG = blurG[i]
            val bB = blurB[i]
            val diffLum = abs(luminance(oR, oG, oB) - luminance(bR, bG, bB))
            val mask = if (diffLum <= thr) {
                0f
            } else {
                clamp01((diffLum - thr) / (1f - thr + 1e-5f))
            }
            val gain = limit * mask
            val newR = clamp01(oR + (oR - bR) * gain)
            val newG = clamp01(oG + (oG - bG) * gain)
            val newB = clamp01(oB + (oB - bB) * gain)
            val alpha = Color.alpha(pixels[i])
            sharpened[i] = composeColor(alpha, newR, newG, newB)
        }
        return ImageBuffer(width, height, sharpened)
    }

    private fun gaussianBlur(
        srcR: FloatArray,
        srcG: FloatArray,
        srcB: FloatArray,
        width: Int,
        height: Int,
        radius: Int,
    ): Triple<FloatArray, FloatArray, FloatArray> {
        val kernel = gaussianKernel(radius)
        val tempR = FloatArray(width * height)
        val tempG = FloatArray(width * height)
        val tempB = FloatArray(width * height)
        val size = kernel.size
        for (y in 0 until height) {
            val base = y * width
            for (x in 0 until width) {
                var rAcc = 0f
                var gAcc = 0f
                var bAcc = 0f
                for (k in 0 until size) {
                    val offsetX = (x + k - radius).coerceIn(0, width - 1)
                    val idx = base + offsetX
                    val weight = kernel[k]
                    rAcc += srcR[idx] * weight
                    gAcc += srcG[idx] * weight
                    bAcc += srcB[idx] * weight
                }
                val index = base + x
                tempR[index] = rAcc
                tempG[index] = gAcc
                tempB[index] = bAcc
            }
        }

        val outR = FloatArray(width * height)
        val outG = FloatArray(width * height)
        val outB = FloatArray(width * height)
        for (x in 0 until width) {
            for (y in 0 until height) {
                var rAcc = 0f
                var gAcc = 0f
                var bAcc = 0f
                for (k in 0 until size) {
                    val offsetY = (y + k - radius).coerceIn(0, height - 1)
                    val idx = offsetY * width + x
                    val weight = kernel[k]
                    rAcc += tempR[idx] * weight
                    gAcc += tempG[idx] * weight
                    bAcc += tempB[idx] * weight
                }
                val index = y * width + x
                outR[index] = rAcc
                outG[index] = gAcc
                outB[index] = bAcc
            }
        }
        return Triple(outR, outG, outB)
    }

    private fun gaussianKernel(radius: Int): FloatArray {
        val size = radius * 2 + 1
        val sigma = max(radius / 2f, 1f)
        val kernel = FloatArray(size)
        var sum = 0f
        for (i in 0 until size) {
            val x = (i - radius).toFloat()
            val value = exp(-(x * x) / (2f * sigma * sigma))
            kernel[i] = value
            sum += value
        }
        if (sum > 0f) {
            for (i in 0 until size) {
                kernel[i] /= sum
            }
        }
        return kernel
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
        val backend: ModelBackend
        val checksum: String
        suspend fun enhance(buffer: ImageBuffer, delegate: Delegate, iterations: Int): ModelResult
    }

    interface RestormerModel {
        val backend: ModelBackend
        val checksum: String
        suspend fun denoise(tile: ImageBuffer, delegate: Delegate): ModelResult
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
        val isLowLight: Boolean,
        val kDce: Float,
        val restormerMix: Float,
        val alphaDetail: Float,
        val sharpenAmount: Float,
        val sharpenRadius: Float,
        val sharpenThreshold: Float,
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
            val t = strength.coerceIn(0f, 1f)
            val eased = easeInOut(t)

            val lMean = metrics.lMean.toFloat()
            val darkness = metrics.pDark.toFloat()
            val sharpness = metrics.bSharpness.toFloat()
            val noise = metrics.nNoise.toFloat()

            val lowLightByLuma = mapLow(lMean, 0.48f, 0.25f)
            val lowLightByDark = mapLow(darkness, 0.62f, 0.28f)
            val lowLightScore = clamp01(lowLightByLuma * 0.65f + lowLightByDark * 0.35f)
            val isLowLight = lowLightScore > 0.1f
            val kDce = clamp(lowLightScore * eased, 0f, 1f)

            val restormerMix = clamp(noise * eased, 0f, 1f)

            val detailDeficit = mapLow(sharpness, 0.58f, 0.33f)
            val alphaDetail = clamp(restormerMix * detailDeficit * clamp(1f - noise, 0f, 1f), 0f, 1f)

            var sharpenAmount = clamp(0.18f + 0.52f * detailDeficit - 0.32f * noise, 0f, 0.7f) * eased
            val sharpenRadius = clamp(1.15f + 1.8f * mapLow(sharpness, 0.44f, 0.22f), 0.8f, 3.2f)
            val sharpenThreshold = clamp(0.018f + 0.16f * mapLow(noise, 0.3f, 0.3f), 0.012f, 0.1f)
            if (noise > 0.6f && t < 0.4f) {
                sharpenAmount = 0f
            }

            val vibranceBase = mapLow(lMean, 0.52f, 0.27f)
            val vibranceGain = clamp(vibranceBase * (1f - 0.6f * noise) * eased * 0.65f, 0f, 1f)

            val saturationBase = mapLow(lMean, 0.6f, 0.35f)
            val saturationGain = clamp(1f + (0.22f + 0.35f * saturationBase) * eased - 0.18f * noise, 0.85f, 1.5f)

            return Profile(
                isLowLight = isLowLight,
                kDce = kDce,
                restormerMix = restormerMix,
                alphaDetail = alphaDetail,
                sharpenAmount = sharpenAmount,
                sharpenRadius = sharpenRadius,
                sharpenThreshold = sharpenThreshold,
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
        private const val LOG_TAG = "Enhance/Engine"
        private const val DEFAULT_TILE_SIZE = 384
        private const val DEFAULT_TILE_OVERLAP = 32
        private const val DEFAULT_ZERO_DCE_ITERATIONS = 8
        private const val OUTPUT_JPEG_QUALITY = 92
        private const val DARK_LUMINANCE_THRESHOLD = 0.22
        private const val SOBEL_NORMALIZATION = 4.5
        private const val NOISE_NORMALIZATION = 0.12

        private val EXIF_TAGS = arrayOf(
            ExifInterface.TAG_DATETIME,
            ExifInterface.TAG_DATETIME_ORIGINAL,
            ExifInterface.TAG_DATETIME_DIGITIZED,
            ExifInterface.TAG_SUBSEC_TIME,
            ExifInterface.TAG_SUBSEC_TIME_ORIGINAL,
            ExifInterface.TAG_SUBSEC_TIME_DIGITIZED,
            ExifInterface.TAG_MAKE,
            ExifInterface.TAG_MODEL,
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.TAG_FLASH,
            ExifInterface.TAG_FOCAL_LENGTH,
            ExifInterface.TAG_WHITE_BALANCE,
            ExifInterface.TAG_EXPOSURE_TIME,
            ExifInterface.TAG_APERTURE,
            ExifInterface.TAG_ISO_SPEED_RATINGS,
            ExifInterface.TAG_GPS_LATITUDE_REF,
            ExifInterface.TAG_GPS_LATITUDE,
            ExifInterface.TAG_GPS_LONGITUDE_REF,
            ExifInterface.TAG_GPS_LONGITUDE,
            ExifInterface.TAG_GPS_ALTITUDE_REF,
            ExifInterface.TAG_GPS_ALTITUDE,
            ExifInterface.TAG_GPS_TIMESTAMP,
            ExifInterface.TAG_GPS_DATESTAMP,
            ExifInterface.TAG_GPS_PROCESSING_METHOD,
            ExifInterface.TAG_GPS_SPEED_REF,
            ExifInterface.TAG_GPS_SPEED,
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

private fun clamp(value: Float, min: Float, max: Float): Float {
    return when {
        value < min -> min
        value > max -> max
        else -> value
    }
}

private fun mapLow(value: Float, pivot: Float, softness: Float): Float {
    if (softness <= 1e-6f) {
        return if (value < pivot) 1f else 0f
    }
    val normalized = (pivot - value) / softness
    return clamp01(normalized)
}

private fun composeColor(alpha: Int, r: Float, g: Float, b: Float): Int {
    val rr = (r * 255f).roundToInt().coerceIn(0, 255)
    val gg = (g * 255f).roundToInt().coerceIn(0, 255)
    val bb = (b * 255f).roundToInt().coerceIn(0, 255)
    return Color.argb(alpha, rr, gg, bb)
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
