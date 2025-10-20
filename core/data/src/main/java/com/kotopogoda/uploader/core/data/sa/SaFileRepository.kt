package com.kotopogoda.uploader.core.data.sa

import android.app.PendingIntent
import android.app.RecoverableSecurityException
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import com.kotopogoda.uploader.core.data.upload.UploadLog
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

@Singleton
class SaFileRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val processingFolderProvider: ProcessingFolderProvider
) {

    suspend fun moveToProcessing(src: Uri): MoveResult = withContext(Dispatchers.IO) {
        Timber.tag(STORAGE_TAG).i(
            UploadLog.message(
                category = CATEGORY_MOVE_REQUEST,
                action = "to_processing",
                uri = src,
            ),
        )
        val destinationDirectory = processingFolderProvider.ensure()

        val result = if (isMediaStoreUri(src)) {
            moveMediaStoreDocument(src, destinationDirectory)
        } else {
            moveSafDocument(src, destinationDirectory)
        }
        when (result) {
            is MoveResult.Success -> Timber.tag(STORAGE_TAG).i(
                UploadLog.message(
                    category = CATEGORY_MOVE_OK,
                    action = "to_processing",
                    uri = src,
                    details = arrayOf(
                        "destination" to result.uri,
                    ),
                ),
            )
            is MoveResult.RequiresWritePermission -> Timber.tag(STORAGE_TAG).i(
                UploadLog.message(
                    category = CATEGORY_PERMISSION_WRITE,
                    action = "to_processing",
                    uri = src,
                ),
            )
            is MoveResult.RequiresDeletePermission -> Timber.tag(STORAGE_TAG).i(
                UploadLog.message(
                    category = CATEGORY_PERMISSION_DELETE,
                    action = "to_processing",
                    uri = src,
                ),
            )
        }
        result
    }

    suspend fun moveBack(srcInProcessing: Uri, originalParent: Uri, displayName: String): Uri =
        withContext(Dispatchers.IO) {
            Timber.tag(STORAGE_TAG).i(
                UploadLog.message(
                    category = CATEGORY_MOVE_BACK_REQUEST,
                    action = "from_processing",
                    uri = srcInProcessing,
                    details = arrayOf(
                        "target_parent" to originalParent,
                    ),
                ),
            )
            val source = DocumentFile.fromSingleUri(context, srcInProcessing)
                ?: throw IllegalStateException("Source document not found for $srcInProcessing")
            val parent = DocumentFile.fromTreeUri(context, originalParent)
                ?: throw IllegalStateException("Original parent document missing for $originalParent")

            val mimeType = source.type ?: DEFAULT_MIME
            val destination = createUniqueFile(parent, mimeType, displayName)
                ?: throw IllegalStateException("Unable to recreate destination $displayName")

            copyDocument(srcInProcessing, destination.uri)

            if (!source.delete()) {
                destination.delete()
                throw IllegalStateException("Unable to delete temporary document $srcInProcessing")
            }

            Timber.tag(STORAGE_TAG).i(
                UploadLog.message(
                    category = CATEGORY_MOVE_BACK_OK,
                    action = "from_processing",
                    uri = destination.uri,
                    details = arrayOf(
                        "source" to srcInProcessing,
                    ),
                ),
            )

            destination.uri
        }

    private fun copyDocument(from: Uri, to: Uri) {
        val resolver: ContentResolver = context.contentResolver
        val input = resolver.openInputStream(from)
            ?: throw IllegalStateException("Unable to open input stream for $from")
        val output = resolver.openOutputStream(to)
            ?: throw IllegalStateException("Unable to open output stream for $to")

        input.use { sourceStream ->
            output.use { destinationStream ->
                val buffer = ByteArray(BUFFER_SIZE)
                while (true) {
                    val read = sourceStream.read(buffer)
                    if (read <= 0) {
                        if (read == -1) {
                            break
                        }
                        continue
                    }
                    destinationStream.write(buffer, 0, read)
                }
                destinationStream.flush()
            }
        }
    }

    private fun moveSafDocument(src: Uri, destinationDirectory: DocumentFile): MoveResult {
        val source = DocumentFile.fromSingleUri(context, src)
            ?: throw IllegalStateException("Source document not found for $src")

        val mimeType = source.type ?: DEFAULT_MIME
        val displayName = source.name ?: DEFAULT_FILE_NAME
        val destination = createUniqueFile(destinationDirectory, mimeType, displayName)
            ?: throw IllegalStateException("Unable to create destination document for $src")

        copyDocument(src, destination.uri)

        if (!source.delete()) {
            destination.delete()
            throw IllegalStateException("Unable to delete source document $src")
        }

        return MoveResult.Success(destination.uri)
    }

    private fun moveMediaStoreDocument(src: Uri, destinationDirectory: DocumentFile): MoveResult {
        val resolver = context.contentResolver

        val inPlaceResult = tryMoveMediaStoreDocument(resolver, src, destinationDirectory)
        if (inPlaceResult != null) {
            return inPlaceResult
        }

        val mimeType = resolver.getType(src) ?: DEFAULT_MIME
        val displayName = resolveMediaStoreDisplayName(resolver, src) ?: DEFAULT_FILE_NAME
        val destinationDocumentId = resolveDocumentId(destinationDirectory)
        val destinationVolume = destinationDocumentId?.substringBefore(':')
        val mediaStoreVolume = runCatching { MediaStore.getVolumeName(src) }.getOrNull()

        if (
            destinationDocumentId != null &&
            destinationVolume != null &&
            mediaStoreVolume != null &&
            areSameVolume(destinationVolume, mediaStoreVolume)
        ) {
            val uniqueDisplayName = generateUniqueDisplayName(destinationDirectory, displayName)
            val relativePath = buildRelativePath(destinationDocumentId)
                ?: throw IllegalStateException("Unable to resolve relative path for ${destinationDirectory.uri}")

            val updateValues = ContentValues().apply {
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                if (uniqueDisplayName != displayName) {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, uniqueDisplayName)
                }
            }

            val updateResult = runCatching { resolver.update(src, updateValues, null, null) }
            val updated = updateResult.getOrElse { error ->
                return handleRecoverableWrite(resolver, src, error)
                    ?: throw IllegalStateException("Unable to update destination for $src", error)
            }
            if (updated <= 0) {
                throw IllegalStateException("Unable to update destination for $src")
            }

            val targetDocumentId = "$destinationDocumentId/$uniqueDisplayName"
            return MoveResult.Success(
                DocumentsContract.buildDocumentUriUsingTree(destinationDirectory.uri, targetDocumentId)
            )
        }

        val destination = createUniqueFile(destinationDirectory, mimeType, displayName)
            ?: throw IllegalStateException("Unable to create destination document for $src")

        copyDocument(src, destination.uri)

        val deleteResult = deleteMediaStoreSource(resolver, src)
        if (deleteResult != null) {
            destination.delete()
            return deleteResult
        }

        return MoveResult.Success(destination.uri)
    }

    private fun tryMoveMediaStoreDocument(
        resolver: ContentResolver,
        src: Uri,
        destinationDirectory: DocumentFile
    ): MoveResult? {
        val sourceVolume = resolveMediaStoreVolume(src) ?: return null
        val destinationLocation = resolveDocumentLocation(destinationDirectory) ?: return null
        if (destinationLocation.volume != sourceVolume) {
            return null
        }

        val updateResult = runCatching {
            resolver.update(
                src,
                ContentValues().apply {
                    put(MediaStore.MediaColumns.RELATIVE_PATH, ensureTrailingSlash(destinationLocation.relativePath))
                },
                null,
                null
            )
        }
        val updated = updateResult.getOrElse { error ->
            return handleRecoverableWrite(resolver, src, error)
        }
        if (updated <= 0) {
            throw IllegalStateException("Unable to update relative path for $src")
        }

        return MoveResult.Success(src)
    }

    private fun deleteMediaStoreSource(resolver: ContentResolver, uri: Uri): MoveResult? {
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            val deleted = resolver.delete(uri, null, null)
            if (deleted <= 0) {
                throw IllegalStateException("Unable to delete source document $uri")
            }
            return null
        }

        val deleteResult = runCatching { resolver.delete(uri, null, null) }
        val deleted = deleteResult.getOrElse { error ->
            val recoverable = error as? RecoverableSecurityException
                ?: throw IllegalStateException("Unable to delete source document $uri", error)
            return MoveResult.RequiresDeletePermission(
                MediaStore.createDeleteRequest(resolver, listOf(uri))
            )
        }
        if (deleted <= 0) {
            val stillExists = resolver.query(uri, arrayOf(MediaStore.MediaColumns._ID), null, null, null)
                ?.use { cursor -> cursor.moveToFirst() }
            if (stillExists != true) {
                return null
            }
            throw IllegalStateException("Unable to delete source document $uri")
        }
        return null
    }

    private fun handleRecoverableWrite(
        resolver: ContentResolver,
        uri: Uri,
        error: Throwable
    ): MoveResult? {
        val recoverable = error as? RecoverableSecurityException ?: return null
        val pendingIntent = runCatching {
            MediaStore.createWriteRequest(resolver, listOf(uri))
        }.getOrNull()
        return pendingIntent?.let { MoveResult.RequiresWritePermission(it) }
    }

    private fun resolveMediaStoreDisplayName(resolver: ContentResolver, uri: Uri): String? {
        val projection = arrayOf(MediaStore.MediaColumns.DISPLAY_NAME)
        val cursor = resolver.query(uri, projection, null, null, null) ?: return null
        cursor.use { result ->
            if (!result.moveToFirst()) {
                return null
            }
            val columnIndex = result.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
            return if (columnIndex >= 0 && !result.isNull(columnIndex)) {
                result.getString(columnIndex)
            } else {
                null
            }
        }
    }

    private fun isMediaStoreUri(uri: Uri): Boolean {
        return uri.authority == MediaStore.AUTHORITY
    }

    private fun resolveMediaStoreVolume(uri: Uri): String? {
        val segments = uri.pathSegments
        if (segments.isEmpty()) {
            return null
        }
        return segments.firstOrNull()?.takeIf { it.isNotEmpty() }
    }

    private fun resolveDocumentLocation(document: DocumentFile): DocumentLocation? {
        val documentId = runCatching { DocumentsContract.getDocumentId(document.uri) }.getOrNull()
            ?: return null
        val separatorIndex = documentId.indexOf(':')
        if (separatorIndex <= 0 || separatorIndex >= documentId.lastIndex) {
            return null
        }
        val volume = documentId.substring(0, separatorIndex)
        val relativePath = documentId.substring(separatorIndex + 1)
        if (volume.isEmpty() || relativePath.isEmpty()) {
            return null
        }
        return DocumentLocation(volume = volume, relativePath = relativePath)
    }

    private fun ensureTrailingSlash(path: String): String {
        return if (path.endsWith('/')) {
            path
        } else {
            "$path/"
        }
    }

    private fun createUniqueFile(
        destinationDirectory: DocumentFile,
        mimeType: String,
        displayName: String
    ): DocumentFile? {
        val uniqueName = generateUniqueDisplayName(destinationDirectory, displayName)
        return destinationDirectory.createFile(mimeType, uniqueName)
    }

    private fun generateUniqueDisplayName(
        destinationDirectory: DocumentFile,
        originalDisplayName: String
    ): String {
        val originalComponents = parseDisplayName(originalDisplayName)

        val usedSuffixes = mutableSetOf<Int>()
        var hasExactMatch = false

        destinationDirectory.listFiles().forEach { existing ->
            if (!existing.isFile) {
                return@forEach
            }
            val existingName = existing.name ?: return@forEach
            val existingComponents = parseDisplayName(existingName)
            if (!existingComponents.sharesRootWith(originalComponents)) {
                return@forEach
            }

            if (existingName == originalDisplayName) {
                hasExactMatch = true
            }

            usedSuffixes += existingComponents.suffix ?: 0
        }

        if (!hasExactMatch) {
            return originalDisplayName
        }

        var candidateSuffix = maxOf(originalComponents.nextSuffixCandidate, 1)
        while (usedSuffixes.contains(candidateSuffix)) {
            candidateSuffix += 1
        }

        return buildDisplayName(originalComponents.baseRoot, originalComponents.extension, candidateSuffix)
    }

    private fun parseDisplayName(displayName: String): DisplayNameComponents {
        val (baseName, extension) = splitExtension(displayName)
        val lastHyphenIndex = baseName.lastIndexOf('-')
        if (lastHyphenIndex > 0 && lastHyphenIndex + 1 < baseName.length) {
            val suffixCandidate = baseName.substring(lastHyphenIndex + 1)
            val suffix = suffixCandidate.toIntOrNull()
            if (suffix != null) {
                val baseRoot = baseName.substring(0, lastHyphenIndex)
                if (baseRoot.isNotEmpty()) {
                    return DisplayNameComponents(
                        baseRoot = baseRoot,
                        extension = extension,
                        suffix = suffix,
                        nextSuffixCandidate = suffix + 1
                    )
                }
            }
        }

        return DisplayNameComponents(
            baseRoot = baseName,
            extension = extension,
            suffix = null,
            nextSuffixCandidate = 1
        )
    }

    private fun buildDisplayName(baseRoot: String, extension: String?, suffix: Int?): String {
        val basePart = if (suffix != null) {
            "$baseRoot-$suffix"
        } else {
            baseRoot
        }

        return if (extension.isNullOrEmpty()) {
            basePart
        } else {
            "$basePart.$extension"
        }
    }

    private fun splitExtension(displayName: String): Pair<String, String?> {
        val lastDotIndex = displayName.lastIndexOf('.')
        if (lastDotIndex <= 0 || lastDotIndex == displayName.lastIndex) {
            return displayName to null
        }

        val baseName = displayName.substring(0, lastDotIndex)
        val extension = displayName.substring(lastDotIndex + 1)
        return baseName to extension
    }

    companion object {
        private const val BUFFER_SIZE = 1024 * 1024
        private const val DEFAULT_MIME = "application/octet-stream"
        private const val DEFAULT_FILE_NAME = "photo.jpg"
        private const val STORAGE_TAG = "Storage"
        private const val CATEGORY_MOVE_REQUEST = "STORAGE/MOVE_REQUEST"
        private const val CATEGORY_MOVE_OK = "STORAGE/MOVE_OK"
        private const val CATEGORY_PERMISSION_WRITE = "STORAGE/REQUEST_WRITE"
        private const val CATEGORY_PERMISSION_DELETE = "STORAGE/REQUEST_DELETE"
        private const val CATEGORY_MOVE_BACK_REQUEST = "STORAGE/MOVE_BACK_REQUEST"
        private const val CATEGORY_MOVE_BACK_OK = "STORAGE/MOVE_BACK_OK"
    }
}

sealed class MoveResult {
    data class Success(val uri: Uri) : MoveResult()
    data class RequiresWritePermission(val pendingIntent: PendingIntent) : MoveResult()
    data class RequiresDeletePermission(val pendingIntent: PendingIntent) : MoveResult()
}

private fun areSameVolume(destinationVolume: String, mediaStoreVolume: String): Boolean {
    val normalizedDestination = destinationVolume.lowercase()
    val normalizedSource = when (mediaStoreVolume.lowercase()) {
        MediaStore.VOLUME_EXTERNAL.lowercase(),
        MediaStore.VOLUME_EXTERNAL_PRIMARY.lowercase() -> "primary"
        else -> mediaStoreVolume.lowercase()
    }

    return normalizedDestination == normalizedSource
}

private fun buildRelativePath(destinationDocumentId: String): String? {
    val separatorIndex = destinationDocumentId.indexOf(':')
    if (separatorIndex <= 0 || separatorIndex >= destinationDocumentId.length - 1) {
        return null
    }
    val relative = destinationDocumentId.substring(separatorIndex + 1)
    return if (relative.endsWith('/')) {
        relative
    } else {
        "$relative/"
    }
}

private fun resolveDocumentId(document: DocumentFile): String? {
    return runCatching { DocumentsContract.getDocumentId(document.uri) }.getOrNull()
}

private data class DisplayNameComponents(
    val baseRoot: String,
    val extension: String?,
    val suffix: Int?,
    val nextSuffixCandidate: Int
) {
    fun sharesRootWith(other: DisplayNameComponents): Boolean {
        if (!extensionsMatch(extension, other.extension)) {
            return false
        }
        return baseRoot == other.baseRoot
    }
}

private data class DocumentLocation(
    val volume: String,
    val relativePath: String
)

private fun extensionsMatch(first: String?, second: String?): Boolean {
    if (first.isNullOrEmpty() || second.isNullOrEmpty()) {
        return first.isNullOrEmpty() && second.isNullOrEmpty()
    }
    return first.equals(second, ignoreCase = true)
}
