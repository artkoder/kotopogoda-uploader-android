package com.kotopogoda.uploader.core.logging.diagnostics


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
