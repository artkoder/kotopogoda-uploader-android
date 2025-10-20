package com.kotopogoda.uploader.core.logging.diagnostics

import com.kotopogoda.uploader.core.data.folder.FolderRepository
import javax.inject.Inject
import javax.inject.Singleton

data class FolderSnapshot(
    val treeUri: String,
    val flags: Int,
    val lastScanAt: Long?,
    val lastViewedPhotoId: String?,
    val lastViewedAt: Long?,
)

interface FolderSelectionProvider {
    suspend fun currentSelection(): FolderSnapshot?
}

@Singleton
class RepositoryFolderSelectionProvider @Inject constructor(
    private val repository: FolderRepository,
) : FolderSelectionProvider {
    override suspend fun currentSelection(): FolderSnapshot? {
        return repository.getFolder()?.let { folder ->
            FolderSnapshot(
                treeUri = folder.treeUri,
                flags = folder.flags,
                lastScanAt = folder.lastScanAt,
                lastViewedPhotoId = folder.lastViewedPhotoId,
                lastViewedAt = folder.lastViewedAt,
            )
        }
    }
}
