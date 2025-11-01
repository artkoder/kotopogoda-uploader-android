package com.kotopogoda.uploader.feature.viewer

import android.content.Context
import android.content.ContentResolver
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.kotopogoda.uploader.core.data.util.logUriReadDebug
import com.kotopogoda.uploader.core.data.util.requireOriginalIfNeeded

/**
 * Флаги отражения изображения на основе EXIF ориентации.
 */
data class FlipFlags(val flipX: Boolean, val flipY: Boolean)

/**
 * Разрешает флаги отражения из EXIF метаданных изображения.
 */
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
