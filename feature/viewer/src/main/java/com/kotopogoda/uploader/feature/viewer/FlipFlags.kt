package com.kotopogoda.uploader.feature.viewer

import android.content.Context
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.kotopogoda.uploader.core.data.util.logUriReadDebug
import com.kotopogoda.uploader.core.data.util.requireOriginalIfNeeded

data class FlipFlags(val flipX: Boolean, val flipY: Boolean)

internal fun resolveFlipFlags(context: Context, uri: Uri, tag: String = "FlipFlags"): FlipFlags = runCatching {
    val resolver = context.contentResolver
    val normalizedUri = resolver.requireOriginalIfNeeded(uri)
    resolver.logUriReadDebug(tag, uri, normalizedUri)
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
