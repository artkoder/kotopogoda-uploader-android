package com.kotopogoda.uploader.core.data.photo

import android.net.Uri

data class PhotoItem(
    val id: String,
    val uri: Uri,
    val exifDate: Long
)
