package com.kotopogoda.uploader.feature.viewer

import android.content.Context
import android.net.Uri
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

@Composable
fun ZoomableImage(
    uri: Uri,
    modifier: Modifier = Modifier,
    onZoomChanged: (atBaseScale: Boolean) -> Unit = {}
) {
    val context = LocalContext.current
    val imageRequest = remember(uri) {
        ImageRequest.Builder(context)
            .data(uri)
            .size(Size.ORIGINAL)
            .allowHardware(false)
            .build()
    }

    var scale by rememberSaveable(uri) { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    var isAtBaseScale by remember { mutableStateOf(true) }
    val minScale = 1f
    val maxScale = 4f

    LaunchedEffect(uri) {
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

    val flips = remember(uri) { resolveFlipFlags(context, uri, "ZoomableImage.flip") }

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
