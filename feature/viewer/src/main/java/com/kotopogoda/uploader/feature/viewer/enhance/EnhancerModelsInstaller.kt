package com.kotopogoda.uploader.feature.viewer.enhance

import android.content.Context
import android.content.res.AssetManager
import com.kotopogoda.uploader.core.data.ml.ModelDefinition
import com.kotopogoda.uploader.core.data.ml.ModelsLock
import com.kotopogoda.uploader.core.data.ml.ModelsLockParser
import com.kotopogoda.uploader.feature.viewer.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.security.DigestInputStream
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.io.DEFAULT_BUFFER_SIZE
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber

@Singleton
class EnhancerModelsInstaller @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val assetManager: AssetManager = context.assets
    private val installDir: File = File(context.filesDir, "models")
    private val modelsLock: ModelsLock by lazy(LazyThreadSafetyMode.NONE) {
        ModelsLockParser.parse(BuildConfig.MODELS_LOCK_JSON)
    }
    private val mutex = Mutex()

    suspend fun ensureInstalled(): File = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (!installDir.exists()) {
                installDir.mkdirs()
            }
            modelsLock.models.values.forEach { model ->
                installModel(model)
            }
        }
        installDir
    }

    private fun installModel(model: ModelDefinition) {
        model.files.forEach { file ->
            val assetPath = file.assetPath()
            val target = resolveTarget(assetPath)
            val needsCopy = !target.exists() || !verifyFile(target, file.sha256)
            if (needsCopy) {
                Timber.tag(TAG).i("Копируем %s -> %s", assetPath, target.absolutePath)
                copyAsset(assetPath, target)
                if (!verifyFile(target, file.sha256)) {
                    throw IOException("Не удалось проверить контрольную сумму ${file.path}")
                }
            } else {
                Timber.tag(TAG).d("Файл %s уже установлен", target.name)
            }
        }
    }

    private fun resolveTarget(assetPath: String): File {
        val relative = assetPath.removePrefix("models/").removePrefix("/")
        return File(installDir, relative)
    }

    private fun copyAsset(assetPath: String, destination: File) {
        destination.parentFile?.mkdirs()
        assetManager.open(assetPath).use { input ->
            destination.outputStream().use { output ->
                input.copyTo(output)
            }
        }
    }

    private fun verifyFile(file: File, expectedSha: String): Boolean {
        if (!file.exists()) return false
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            DigestInputStream(input, digest).use { stream ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = stream.read(buffer)
                    if (read <= 0) break
                }
            }
        }
        val actual = digest.digest().joinToString(separator = "") { byte -> "%02x".format(byte) }
        val matches = actual.equals(expectedSha, ignoreCase = true)
        if (!matches) {
            Timber.tag(TAG).w(
                "Несовпадение SHA-256 для %s: ожидалось=%s, фактически=%s",
                file.absolutePath,
                expectedSha,
                actual,
            )
        }
        return matches
    }

    companion object {
        private const val TAG = "EnhancerInstaller"
    }
}
