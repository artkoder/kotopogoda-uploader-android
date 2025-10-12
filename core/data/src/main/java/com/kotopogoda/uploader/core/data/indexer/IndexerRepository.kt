package com.kotopogoda.uploader.core.data.indexer

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import androidx.exifinterface.media.ExifInterface
import com.kotopogoda.uploader.core.data.folder.FolderRepository
import com.kotopogoda.uploader.core.data.photo.PhotoDao
import com.kotopogoda.uploader.core.data.photo.PhotoEntity
import com.kotopogoda.uploader.core.data.photo.PhotoStatus
import com.kotopogoda.uploader.core.data.util.ExifDateParser
import com.kotopogoda.uploader.core.data.util.Hashing
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

@Singleton
class IndexerRepository @Inject constructor(
    private val context: Context,
    private val folderRepository: FolderRepository,
    private val photoDao: PhotoDao,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {

    fun scanAll(): Flow<ScanProgress> = flow {
        val folder = folderRepository.getFolder()
            ?: throw IllegalStateException("Root folder is not selected")
        val treeUri = Uri.parse(folder.treeUri)
        val rootDocument = DocumentFile.fromTreeUri(context, treeUri)
            ?: throw IllegalStateException("Unable to resolve tree URI: ${folder.treeUri}")

        var progress = ScanProgress()
        emit(progress)

        withContext(ioDispatcher) {
            traverse(rootDocument) { outcome ->
                progress = progress.advance(outcome)
                this@flow.emit(progress)
            }
        }
    }

    private suspend fun traverse(document: DocumentFile, onOutcome: suspend (ScanOutcome) -> Unit) {
        val coroutineContext = currentCoroutineContext()
        if (!coroutineContext.isActive) {
            return
        }

        if (document.isDirectory) {
            val children = runCatching { document.listFiles() }
                .onFailure { error ->
                    Log.w(TAG, "Failed to list children for ${document.uri}", error)
                }
                .getOrNull()
                ?: return

            for (child in children) {
                currentCoroutineContext().ensureActive()
                traverse(child, onOutcome)
            }
        } else if (document.isFile && document.isJpegFile()) {
            val outcome = processFile(document)
            if (outcome != null) {
                onOutcome(outcome)
            }
        }
    }

    private suspend fun processFile(document: DocumentFile): ScanOutcome? {
        val uri = document.uri
        val id = uri.toString()
        val mime = document.type ?: DEFAULT_MIME
        val size = max(0L, document.length())
        val lastModified = document.lastModified()
        val relPath = resolveRelativePath(uri)

        val sha256 = try {
            Hashing.sha256 {
                context.contentResolver.openInputStream(uri)
                    ?: throw IllegalStateException("Unable to open stream for $uri")
            }
        } catch (error: Exception) {
            Log.w(TAG, "Unable to hash file $uri", error)
            return ScanOutcome.SKIPPED
        }

        val takenAt = readTakenAtTimestamp(uri, lastModified)
        val existing = runCatching { photoDao.getById(id) }.getOrElse { error ->
            Log.w(TAG, "Failed to load existing record for $id", error)
            return ScanOutcome.SKIPPED
        }
        val duplicate = runCatching { photoDao.getBySha256(sha256) }.getOrElse { error ->
            Log.w(TAG, "Failed to check duplicates for $id", error)
            return ScanOutcome.SKIPPED
        }

        if (duplicate != null && duplicate.id != id) {
            Log.i(TAG, "Duplicate detected for $id (matches ${duplicate.id}), skipping")
            return ScanOutcome.SKIPPED
        }

        val entity = PhotoEntity(
            id = id,
            uri = id,
            relPath = relPath,
            sha256 = sha256,
            takenAt = takenAt,
            size = size,
            mime = mime,
            status = existing?.status ?: PhotoStatus.NEW.value,
            lastActionAt = existing?.lastActionAt
        )

        if (existing == null) {
            return runCatching {
                photoDao.upsert(entity)
                ScanOutcome.INSERTED
            }.getOrElse { error ->
                Log.w(TAG, "Failed to insert $id", error)
                ScanOutcome.SKIPPED
            }
        }

        if (existing.relPath == entity.relPath &&
            existing.sha256 == entity.sha256 &&
            existing.takenAt == entity.takenAt &&
            existing.size == entity.size &&
            existing.mime == entity.mime
        ) {
            return ScanOutcome.SKIPPED
        }

        return runCatching {
            photoDao.upsert(entity)
            ScanOutcome.UPDATED
        }.getOrElse { error ->
            Log.w(TAG, "Failed to update $id", error)
            ScanOutcome.SKIPPED
        }
    }

    private fun readTakenAtTimestamp(uri: Uri, lastModified: Long): Long? {
        val resolver = context.contentResolver
        val exifTimestamp = try {
            resolver.openInputStream(uri)?.use { inputStream ->
                val exif = ExifInterface(inputStream)
                ExifDateParser.extractCaptureTimestampMillis(exif)
            }
        } catch (error: Exception) {
            Log.w(TAG, "Failed to read EXIF for $uri", error)
            null
        }
        if (exifTimestamp != null) {
            return exifTimestamp
        }

        if (lastModified > 0) {
            return lastModified
        }

        return resolver.query(uri, arrayOf(OpenableColumns.DATE_MODIFIED), null, null, null)
            ?.use { cursor ->
                if (!cursor.moveToFirst()) {
                    return@use null
                }
                if (cursor.isNull(0)) {
                    return@use null
                }
                val value = cursor.getLong(0)
                if (value > 0) value else null
            }
    }

    private fun resolveRelativePath(uri: Uri): String? {
        return runCatching {
            val documentId = DocumentsContract.getDocumentId(uri)
            val delimiterIndex = documentId.indexOf(':')
            if (delimiterIndex >= 0 && delimiterIndex + 1 < documentId.length) {
                documentId.substring(delimiterIndex + 1)
            } else {
                null
            }
        }.getOrElse { error ->
            Log.w(TAG, "Failed to resolve relative path for $uri", error)
            null
        }
    }

    private fun DocumentFile.isJpegFile(): Boolean {
        val name = name ?: return false
        val normalized = name.lowercase(Locale.ROOT)
        return normalized.endsWith(".jpg") || normalized.endsWith(".jpeg")
    }

    data class ScanProgress(
        val scanned: Int = 0,
        val inserted: Int = 0,
        val updated: Int = 0,
        val skipped: Int = 0
    )

    private enum class ScanOutcome {
        INSERTED,
        UPDATED,
        SKIPPED
    }

    private fun ScanProgress.advance(outcome: ScanOutcome): ScanProgress {
        val newScanned = scanned + 1
        return when (outcome) {
            ScanOutcome.INSERTED -> copy(
                scanned = newScanned,
                inserted = inserted + 1
            )

            ScanOutcome.UPDATED -> copy(
                scanned = newScanned,
                updated = updated + 1
            )

            ScanOutcome.SKIPPED -> copy(
                scanned = newScanned,
                skipped = skipped + 1
            )
        }
    }

    companion object {
        private const val TAG = "IndexerRepository"
        private const val DEFAULT_MIME = "image/jpeg"
    }
}
