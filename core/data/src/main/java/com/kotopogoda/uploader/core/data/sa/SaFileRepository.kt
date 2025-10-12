package com.kotopogoda.uploader.core.data.sa

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Singleton
class SaFileRepository @Inject constructor(
    private val context: Context,
    private val processingFolderProvider: ProcessingFolderProvider,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {

    suspend fun moveToProcessing(src: Uri): Uri = withContext(ioDispatcher) {
        val source = DocumentFile.fromSingleUri(context, src)
            ?: throw IllegalStateException("Source document not found for $src")

        val destinationDirectory = processingFolderProvider.ensure()
        val mimeType = source.type ?: DEFAULT_MIME
        val displayName = source.name ?: DEFAULT_FILE_NAME
        val destination = destinationDirectory.createFile(mimeType, displayName)
            ?: throw IllegalStateException("Unable to create destination document for $src")

        copyDocument(src, destination.uri)

        if (!source.delete()) {
            destination.delete()
            throw IllegalStateException("Unable to delete source document $src")
        }

        destination.uri
    }

    suspend fun moveBack(srcInProcessing: Uri, originalParent: Uri, displayName: String): Uri =
        withContext(ioDispatcher) {
            val source = DocumentFile.fromSingleUri(context, srcInProcessing)
                ?: throw IllegalStateException("Source document not found for $srcInProcessing")
            val parent = DocumentFile.fromTreeUri(context, originalParent)
                ?: throw IllegalStateException("Original parent document missing for $originalParent")

            val mimeType = source.type ?: DEFAULT_MIME
            val destination = parent.createFile(mimeType, displayName)
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

    companion object {
        private const val BUFFER_SIZE = 64 * 1024
        private const val DEFAULT_MIME = "application/octet-stream"
        private const val DEFAULT_FILE_NAME = "photo.jpg"
    }
}
