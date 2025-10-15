package com.kotopogoda.uploader.core.data.photo

import android.os.Build
import android.provider.MediaStore

internal fun toMediaStoreVolume(volume: String): String {
    val normalized = volume.ifBlank { MediaStore.VOLUME_EXTERNAL }
    return when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && normalized.equals("primary", ignoreCase = true) -> {
            MediaStore.VOLUME_EXTERNAL_PRIMARY
        }
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q -> {
            MediaStore.VOLUME_EXTERNAL
        }
        else -> normalized
    }
}
