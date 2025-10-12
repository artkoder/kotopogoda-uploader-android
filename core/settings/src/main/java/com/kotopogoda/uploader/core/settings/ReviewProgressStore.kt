package com.kotopogoda.uploader.core.settings

import java.time.Instant

interface ReviewProgressStore {
    suspend fun savePosition(folderId: String, index: Int, anchorDate: Instant?)

    suspend fun loadPosition(folderId: String): ReviewPosition?

    suspend fun clear(folderId: String)
}

data class ReviewPosition(
    val index: Int,
    val anchorDate: Instant?
)

fun reviewProgressFolderId(treeUri: String): String {
    val digest = java.security.MessageDigest.getInstance("SHA-256")
    val bytes = digest.digest(treeUri.toByteArray(Charsets.UTF_8))
    return buildString(bytes.size * 2) {
        for (byte in bytes) {
            append(((byte.toInt() ushr 4) and 0xF).toString(16))
            append((byte.toInt() and 0xF).toString(16))
        }
    }
}
