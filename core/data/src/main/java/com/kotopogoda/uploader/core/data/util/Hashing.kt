package com.kotopogoda.uploader.core.data.util

import android.content.ContentResolver
import android.net.Uri
import java.io.InputStream
import java.security.MessageDigest

object Hashing {
    private const val BUFFER_SIZE = 1 * 1024 * 1024 // 1MB

    fun sha256(inputStreamProvider: () -> InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        inputStreamProvider().use { inputStream ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val read = inputStream.read(buffer)
                if (read == -1) {
                    break
                }
                if (read > 0) {
                    digest.update(buffer, 0, read)
                }
            }
        }
        return digest.digest().joinToString(separator = "") { byte ->
            "%02x".format(byte)
        }
    }

    fun sha256(contentResolver: ContentResolver, uri: Uri): String {
        return sha256 {
            contentResolver.openInputStream(uri)
                ?: throw IllegalStateException("Unable to open input stream for uri: $uri")
        }
    }
}
