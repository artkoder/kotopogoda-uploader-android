package com.kotopogoda.uploader.core.data.folder

import android.content.Intent
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "folder",
    indices = [Index(value = ["tree_uri"], unique = true)]
)
data class FolderEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    @ColumnInfo(name = "tree_uri")
    val treeUri: String,
    @ColumnInfo(name = "flags")
    val flags: Int = Intent.FLAG_GRANT_READ_URI_PERMISSION,
    @ColumnInfo(name = "last_scan_at")
    val lastScanAt: Long? = null,
    @ColumnInfo(name = "last_viewed_photo_id")
    val lastViewedPhotoId: String? = null,
    @ColumnInfo(name = "last_viewed_at")
    val lastViewedAt: Long? = null
)
