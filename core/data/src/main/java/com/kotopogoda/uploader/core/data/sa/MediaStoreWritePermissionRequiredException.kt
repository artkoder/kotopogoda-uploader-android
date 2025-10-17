package com.kotopogoda.uploader.core.data.sa

import android.content.IntentSender
import android.net.Uri

class MediaStoreWritePermissionRequiredException(
    val targetUri: Uri,
    val intentSender: IntentSender
) : Exception("Write permission required for $targetUri")
