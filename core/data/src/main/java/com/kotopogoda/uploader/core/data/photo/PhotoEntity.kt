package com.kotopogoda.uploader.core.data.photo

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "photos",
    indices = [
        Index(value = ["sha256"], unique = true),
        Index(value = ["rel_path"])
    ]
)
data class PhotoEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,
    @ColumnInfo(name = "uri")
    val uri: String,
    @ColumnInfo(name = "rel_path")
    val relPath: String?,
    @ColumnInfo(name = "sha256")
    val sha256: String,
    @ColumnInfo(name = "exif_date")
    val exifDate: Long,
    @ColumnInfo(name = "size")
    val size: Long,
    @ColumnInfo(name = "mime", defaultValue = "image/jpeg")
    val mime: String = "image/jpeg",
    @ColumnInfo(name = "status", defaultValue = "new")
    val status: String = PhotoStatus.NEW.value,
    @ColumnInfo(name = "last_action_at")
    val lastActionAt: Long? = null
)
