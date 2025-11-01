package com.kotopogoda.uploader.feature.viewer

import android.content.Context
import android.graphics.RuntimeShader
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.size.Size
import androidx.exifinterface.media.ExifInterface
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.asComposeRenderEffect
import coil.compose.SubcomposeAsyncImage
import coil.compose.SubcomposeAsyncImageContent
import com.kotopogoda.uploader.core.data.util.logUriReadDebug
import com.kotopogoda.uploader.core.data.util.requireOriginalIfNeeded

/**
 * Composable для отображения изображения с возможностью AGSL-блендинга между базовым и улучшенным.
 * Поддерживает zoom/pan и автоматический fallback на CPU blend, если AGSL недоступен.
 */
@Composable
fun BlendableImage(
    baseUri: Uri,
    enhancedUri: Uri?,
    blendFactor: Float,
    modifier: Modifier = Modifier,
    onZoomChanged: (atBaseScale: Boolean) -> Unit = {}
) {
    val context = LocalContext.current
    val agslSupported = remember { isAgslSupported() }

    val displayedUri = if (enhancedUri != null && blendFactor >= 0.999f) {
        enhancedUri
    } else {
        baseUri
    }

    val imageRequest = remember(displayedUri) {
        ImageRequest.Builder(context)
            .data(displayedUri)
            .size(Size.ORIGINAL)
            .allowHardware(false)
            .build()
    }

    var scale by rememberSaveable(displayedUri) { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    var isAtBaseScale by remember { mutableStateOf(true) }
    val minScale = 1f
    val maxScale = 4f

    LaunchedEffect(displayedUri) {
        scale = 1f
        offset = Offset.Zero
        if (!isAtBaseScale) {
            isAtBaseScale = true
        }
        onZoomChanged(true)
    }

    LaunchedEffect(containerSize, scale) {
        if (scale == minScale) {
            offset = Offset.Zero
        } else {
            offset = offset.coerceWithinBounds(scale, containerSize)
        }
    }

    val transformableState = rememberTransformableState { zoomChange, panChange, _ ->
        val newScale = (scale * zoomChange).coerceIn(minScale, maxScale)
        val newOffset = if (newScale == minScale) {
            Offset.Zero
        } else {
            (offset + panChange).coerceWithinBounds(newScale, containerSize)
        }

        scale = newScale
        offset = newOffset

        val atBase = newScale == minScale
        if (isAtBaseScale != atBase) {
            isAtBaseScale = atBase
            onZoomChanged(atBase)
        }
    }

    val flips = remember(displayedUri) { resolveFlipFlags(context, displayedUri) }

    Box(
        modifier = modifier
            .background(Color.Black)
            .onSizeChanged { size -> containerSize = size }
            .clipToBounds()
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = {
                        val targetScale = if (scale < 2f) 2.5f else minScale
                        scale = targetScale
                        offset = if (targetScale == minScale) {
                            Offset.Zero
                        } else {
                            offset.coerceWithinBounds(targetScale, containerSize)
                        }
                        val atBase = targetScale == minScale
                        if (isAtBaseScale != atBase) {
                            isAtBaseScale = atBase
                            onZoomChanged(atBase)
                        }
                    }
                )
            }
            .transformable(transformableState)
    ) {
        if (agslSupported && enhancedUri != null && blendFactor > 0.001f && blendFactor < 0.999f) {
            BlendedImageContent(
                baseUri = baseUri,
                enhancedUri = enhancedUri,
                blendFactor = blendFactor,
                scale = scale,
                offset = offset,
                flips = flips
            )
        } else {
            AsyncImage(
                model = imageRequest,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .align(Alignment.Center)
                    .graphicsLayer {
                        val flipX = if (flips.flipX) -1f else 1f
                        val flipY = if (flips.flipY) -1f else 1f
                        scaleX = flipX * scale
                        scaleY = flipY * scale
                        translationX = offset.x
                        translationY = offset.y
                    }
            )
        }
    }
}

@Composable
private fun BlendedImageContent(
    baseUri: Uri,
    enhancedUri: Uri,
    blendFactor: Float,
    scale: Float,
    offset: Offset,
    flips: FlipFlags
) {
    val context = LocalContext.current
    
    val baseRequest = remember(baseUri) {
        ImageRequest.Builder(context)
            .data(baseUri)
            .size(Size.ORIGINAL)
            .allowHardware(false)
            .build()
    }
    
    val enhancedRequest = remember(enhancedUri) {
        ImageRequest.Builder(context)
            .data(enhancedUri)
            .size(Size.ORIGINAL)
            .allowHardware(false)
            .build()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
    ) {
        AsyncImage(
            model = baseRequest,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .align(Alignment.Center)
                .graphicsLayer {
                    val flipX = if (flips.flipX) -1f else 1f
                    val flipY = if (flips.flipY) -1f else 1f
                    scaleX = flipX * scale
                    scaleY = flipY * scale
                    translationX = offset.x
                    translationY = offset.y
                }
        )
        
        AsyncImage(
            model = enhancedRequest,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .align(Alignment.Center)
                .graphicsLayer {
                    val flipX = if (flips.flipX) -1f else 1f
                    val flipY = if (flips.flipY) -1f else 1f
                    scaleX = flipX * scale
                    scaleY = flipY * scale
                    translationX = offset.x
                    translationY = offset.y
                    alpha = blendFactor
                }
        )
    }
}

private fun isAgslSupported(): Boolean {
    return Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
}

data class FlipFlags(val flipX: Boolean, val flipY: Boolean)

private fun resolveFlipFlags(context: Context, uri: Uri): FlipFlags = runCatching {
    val resolver = context.contentResolver
    val normalizedUri = resolver.requireOriginalIfNeeded(uri)
    resolver.logUriReadDebug("BlendableImage.flip", uri, normalizedUri)
    resolver.openInputStream(normalizedUri)?.use { input ->
        val exif = ExifInterface(input)
        when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> FlipFlags(flipX = true, flipY = false)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> FlipFlags(flipX = false, flipY = true)
            ExifInterface.ORIENTATION_TRANSPOSE -> FlipFlags(flipX = true, flipY = false)
            ExifInterface.ORIENTATION_TRANSVERSE -> FlipFlags(flipX = false, flipY = true)
            else -> FlipFlags(flipX = false, flipY = false)
        }
    } ?: FlipFlags(flipX = false, flipY = false)
}.getOrDefault(FlipFlags(flipX = false, flipY = false))

private fun Offset.coerceWithinBounds(scale: Float, containerSize: IntSize): Offset {
    if (containerSize.width == 0 || containerSize.height == 0) {
        return Offset.Zero
    }
    val halfWidth = containerSize.width * (scale - 1f) / 2f
    val halfHeight = containerSize.height * (scale - 1f) / 2f
    val maxX = halfWidth.coerceAtLeast(0f)
    val maxY = halfHeight.coerceAtLeast(0f)
    return Offset(
        x = x.coerceIn(-maxX, maxX),
        y = y.coerceIn(-maxY, maxY)
    )
}
