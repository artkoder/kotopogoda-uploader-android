package com.kotopogoda.uploader.core.data.sa

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.kotopogoda.uploader.core.data.folder.FolderRepository
import com.kotopogoda.uploader.core.data.upload.UploadLog
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber

@Singleton
class ProcessingFolderProvider @Inject constructor(
    @ApplicationContext private val context: Context,
    private val folderRepository: FolderRepository
) {

    suspend fun ensure(): DocumentFile = withContext(Dispatchers.IO) {
        Timber.tag(STORAGE_TAG).i(
            UploadLog.message(
                category = CATEGORY_FOLDER_REQUEST,
                action = "ensure",
            ),
        )
        val folder = folderRepository.getFolder()
            ?: throw IllegalStateException("Root folder is not selected")
        val treeUri = Uri.parse(folder.treeUri)
        val root = DocumentFile.fromTreeUri(context, treeUri)
            ?: throw IllegalStateException("Unable to resolve tree URI: ${folder.treeUri}")

        val processingFolder = findOrCreateProcessingFolder(root)
        Timber.tag(STORAGE_TAG).i(
            UploadLog.message(
                category = CATEGORY_FOLDER_OK,
                action = "ensure",
                details = arrayOf(
                    "uri" to processingFolder.uri,
                ),
            ),
        )
        processingFolder
    }

    private fun findOrCreateProcessingFolder(root: DocumentFile): DocumentFile {
        val existing = runCatching { root.findFile(PROCESSING_FOLDER_NAME) }.getOrNull()
        if (existing != null && existing.isDirectory) {
            return existing
        }

        val created = root.createDirectory(PROCESSING_FOLDER_NAME)
            ?: throw IllegalStateException("Unable to create processing folder")
        Timber.tag(STORAGE_TAG).i(
            UploadLog.message(
                category = CATEGORY_FOLDER_CREATED,
                action = "ensure",
                details = arrayOf(
                    "uri" to created.uri,
                ),
            ),
        )
        return created
    }

    companion object {
        const val PROCESSING_FOLDER_NAME: String = "На обработку"
        private const val STORAGE_TAG = "Storage"
        private const val CATEGORY_FOLDER_REQUEST = "STORAGE/FOLDER_REQUEST"
        private const val CATEGORY_FOLDER_OK = "STORAGE/FOLDER_READY"
        private const val CATEGORY_FOLDER_CREATED = "STORAGE/FOLDER_CREATED"
    }
}
