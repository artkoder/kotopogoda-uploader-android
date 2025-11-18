package com.kotopogoda.uploader.ml

import android.content.Context
import android.content.res.AssetManager
import com.kotopogoda.uploader.BuildConfig
import com.kotopogoda.uploader.core.data.ml.ModelFile
import com.kotopogoda.uploader.core.data.ml.ModelsLockParser
import com.kotopogoda.uploader.core.data.upload.UploadLog
import java.io.BufferedInputStream
import java.security.DigestInputStream
import java.security.MessageDigest
import kotlin.io.DEFAULT_BUFFER_SIZE
import timber.log.Timber

object ModelChecksumVerifier {

    fun verify(context: Context) {
        val assetManager = context.assets
        val modelsLock = ModelsLockParser.parse(BuildConfig.MODELS_LOCK_JSON)
        modelsLock.models.values.forEach { model ->
            model.files.forEach { file ->
                val assetPath = file.assetPath()
                val actual = calculateChecksum(assetManager, assetPath, file)
                if (!actual.sha.equals(file.sha256, ignoreCase = true)) {
                    val logMessage = UploadLog.message(
                        category = "ML/CHECKSUM",
                        action = "mismatch",
                        details = arrayOf(
                            "model" to model.name,
                            "asset" to assetPath,
                            "expected" to file.sha256,
                            "actual" to actual.sha,
                            "bytes" to actual.bytes,
                        ),
                    )
                    Timber.tag("app").e(logMessage)
                    error("Checksum mismatch for asset ${file.path}")
                }
                if (file.minBytes > 0 && actual.bytes < file.minBytes) {
                    val logMessage = UploadLog.message(
                        category = "ML/CHECKSUM",
                        action = "size_mismatch",
                        details = arrayOf(
                            "model" to model.name,
                            "asset" to assetPath,
                            "expected_min_bytes" to file.minBytes,
                            "actual_bytes" to actual.bytes,
                        ),
                    )
                    Timber.tag("app").e(logMessage)
                    error("Asset ${file.path} is smaller than expected")
                }
                Timber.tag("app").i(
                    UploadLog.message(
                        category = "ML/CHECKSUM",
                        action = "sha256_ok",
                        details = arrayOf(
                            "model" to model.name,
                            "asset" to assetPath,
                            "expected" to file.sha256,
                            "actual" to actual.sha,
                            "bytes" to actual.bytes,
                            "sha256_ok" to true,
                        ),
                    ),
                )
            }
        }
    }

    private fun calculateChecksum(
        assetManager: AssetManager,
        assetPath: String,
        file: ModelFile,
    ): ChecksumResult {
        val digest = MessageDigest.getInstance("SHA-256")
        var totalBytes = 0L
        assetManager.open(assetPath).use { input ->
            DigestInputStream(BufferedInputStream(input), digest).use { stream ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = stream.read(buffer)
                    if (read <= 0) {
                        break
                    }
                    totalBytes += read
                }
            }
        }
        val sha = digest.digest().joinToString(separator = "") { byte -> "%02x".format(byte) }
        return ChecksumResult(sha = sha, bytes = totalBytes)
    }

    private data class ChecksumResult(
        val sha: String,
        val bytes: Long,
    )
}
