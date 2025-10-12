package com.kotopogoda.uploader.core.data.photo

import android.net.Uri
import java.time.Instant

data class PhotoItem(
    val id: String,
    val uri: Uri,
    val takenAt: Instant?
)
