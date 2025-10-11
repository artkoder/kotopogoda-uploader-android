package com.kotopogoda.uploader.core.data.folder

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

@Singleton
class FolderRepository @Inject constructor(
    private val folderDao: FolderDao
) {
    fun observeFolder(): Flow<Folder?> = folderDao.observeFolder().map { entity ->
        entity?.toFolder()
    }

    suspend fun getFolder(): Folder? = folderDao.getFolder()?.toFolder()

    suspend fun setFolder(treeUri: String) {
        folderDao.clear()
        folderDao.insert(FolderEntity(treeUri = treeUri))
    }

    suspend fun clearFolder() {
        folderDao.clear()
    }
}

private fun FolderEntity.toFolder(): Folder = Folder(
    id = id,
    treeUri = treeUri,
    lastScanAt = lastScanAt,
    lastViewedPhotoId = lastViewedPhotoId,
    lastViewedAt = lastViewedAt
)

