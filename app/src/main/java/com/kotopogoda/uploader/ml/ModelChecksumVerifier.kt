package com.kotopogoda.uploader.ml

import android.content.Context
import android.content.res.AssetManager
import com.kotopogoda.uploader.core.data.upload.UploadLog
import java.io.BufferedInputStream
import java.io.BufferedReader
import java.io.InputStreamReader
import java.security.DigestInputStream
import java.security.MessageDigest
import kotlin.io.DEFAULT_BUFFER_SIZE
import timber.log.Timber

object ModelChecksumVerifier {

    private data class ModelAsset(
        val modelPath: String,
        val checksumPath: String,
    )

    private val modelAssets = listOf(
        ModelAsset(
            modelPath = "models/restormer_fp16.tflite",
            checksumPath = "models/restormer_fp16.tflite.sha256",
        ),
        ModelAsset(
            modelPath = "models/zerodcepp_fp16.tflite",
            checksumPath = "models/zerodcepp_fp16.tflite.sha256",
        ),
    )

    fun verify(context: Context) {
        val assetManager = context.assets
        modelAssets.forEach { asset ->
            val expected = readExpectedChecksum(assetManager, asset)
            val actual = calculateChecksum(assetManager, asset)
            if (!expected.equals(actual, ignoreCase = true)) {
                val logMessage = UploadLog.message(
                    category = "ML/CHECKSUM",
                    action = "mismatch",
                    details = arrayOf(
                        "asset" to asset.modelPath,
                        "expected" to expected,
                        "actual" to actual,
                    ),
                )
                Timber.tag("app").e(logMessage)
                error("Checksum mismatch for asset ${asset.modelPath}")
            }
            Timber.tag("app").i(
                UploadLog.message(
                    category = "ML/CHECKSUM",
                    action = "sha256_ok",
                    details = arrayOf(
                        "asset" to asset.modelPath,
                        "expected" to expected,
                        "actual" to actual,
                        "sha256_ok" to true,
                    ),
                ),
            )
        }
    }

    private fun readExpectedChecksum(assetManager: AssetManager, asset: ModelAsset): String {
        assetManager.open(asset.checksumPath).use { input ->
            BufferedReader(InputStreamReader(input)).use { reader ->
                val line = reader.lineSequence()
                    .firstOrNull { it.isNotBlank() }
                    ?.substringBefore(" ")
                    ?.trim()
                return line?.lowercase()
                    ?: error("Checksum file ${asset.checksumPath} is empty")
            }
        }
    }

    private fun calculateChecksum(assetManager: AssetManager, asset: ModelAsset): String {
        val digest = MessageDigest.getInstance("SHA-256")
        assetManager.open(asset.modelPath).use { input ->
            DigestInputStream(BufferedInputStream(input), digest).use { stream ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = stream.read(buffer)
                    if (read <= 0) {
                        break
                    }
                }
            }
        }
        return digest.digest().joinToString(separator = "") { byte ->
            "%02x".format(byte)
        }
    }
}
