package com.kotopogoda.uploader.core.data.sa

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.kotopogoda.uploader.core.data.folder.FolderRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Singleton
class ProcessingFolderProvider @Inject constructor(
    private val context: Context,
    private val folderRepository: FolderRepository,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {

    suspend fun ensure(): DocumentFile = withContext(ioDispatcher) {
        val folder = folderRepository.getFolder()
            ?: throw IllegalStateException("Root folder is not selected")
        val treeUri = Uri.parse(folder.treeUri)
        val root = DocumentFile.fromTreeUri(context, treeUri)
            ?: throw IllegalStateException("Unable to resolve tree URI: ${folder.treeUri}")

        findOrCreateProcessingFolder(root)
    }

    private fun findOrCreateProcessingFolder(root: DocumentFile): DocumentFile {
        val existing = runCatching { root.findFile(PROCESSING_FOLDER_NAME) }.getOrNull()
        if (existing != null && existing.isDirectory) {
            return existing
        }

        return root.createDirectory(PROCESSING_FOLDER_NAME)
            ?: throw IllegalStateException("Unable to create processing folder")
    }

    companion object {
        const val PROCESSING_FOLDER_NAME: String = "На обработку"
    }
}
