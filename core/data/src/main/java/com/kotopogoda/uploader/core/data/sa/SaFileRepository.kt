package com.kotopogoda.uploader.core.data.sa

import android.app.PendingIntent
import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Singleton
class SaFileRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val processingFolderProvider: ProcessingFolderProvider
) {

    suspend fun moveToProcessing(src: Uri): Uri = withContext(Dispatchers.IO) {
        val destinationDirectory = processingFolderProvider.ensure()

        if (isMediaStoreUri(src)) {
            moveMediaStoreDocument(src, destinationDirectory)
        } else {
            moveSafDocument(src, destinationDirectory)
        }
    }

    suspend fun moveBack(srcInProcessing: Uri, originalParent: Uri, displayName: String): Uri =
        withContext(Dispatchers.IO) {
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

    private fun moveSafDocument(src: Uri, destinationDirectory: DocumentFile): Uri {
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

        return destination.uri
    }

    private fun moveMediaStoreDocument(src: Uri, destinationDirectory: DocumentFile): Uri {
        val resolver = context.contentResolver
        val mimeType = resolver.getType(src) ?: DEFAULT_MIME
        val displayName = resolveMediaStoreDisplayName(resolver, src) ?: DEFAULT_FILE_NAME
        val destination = createUniqueFile(destinationDirectory, mimeType, displayName)
            ?: throw IllegalStateException("Unable to create destination document for $src")

        copyDocument(src, destination.uri)

        runCatching { deleteMediaStoreSource(resolver, src) }
            .onFailure {
                destination.delete()
                throw IllegalStateException("Unable to delete source document $src", it)
            }

        return destination.uri
    }

    private fun deleteMediaStoreSource(resolver: ContentResolver, uri: Uri) {
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            val deleted = resolver.delete(uri, null, null)
            if (deleted <= 0) {
                throw IllegalStateException("Unable to delete source document $uri")
            }
            return
        }

        val pendingIntent = MediaStore.createDeleteRequest(resolver, listOf(uri))
        try {
            pendingIntent.send()
        } catch (error: PendingIntent.CanceledException) {
            throw IllegalStateException("Delete request was cancelled for $uri", error)
        }
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
        private const val BUFFER_SIZE = 64 * 1024
        private const val DEFAULT_MIME = "application/octet-stream"
        private const val DEFAULT_FILE_NAME = "photo.jpg"
    }
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

private fun extensionsMatch(first: String?, second: String?): Boolean {
    if (first.isNullOrEmpty() || second.isNullOrEmpty()) {
        return first.isNullOrEmpty() && second.isNullOrEmpty()
    }
    return first.equals(second, ignoreCase = true)
}
