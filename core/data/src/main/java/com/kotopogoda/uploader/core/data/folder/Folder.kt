package com.kotopogoda.uploader.core.data.folder

data class Folder(
    val id: Int,
    val treeUri: String,
    val flags: Int,
    val lastScanAt: Long?,
    val lastViewedPhotoId: String?,
    val lastViewedAt: Long?
)
