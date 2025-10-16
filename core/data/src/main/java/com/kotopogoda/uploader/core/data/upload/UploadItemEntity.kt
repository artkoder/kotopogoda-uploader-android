package com.kotopogoda.uploader.core.data.upload

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "upload_items",
    indices = [
        Index(value = ["uri"], unique = true),
        Index(value = ["state"])
    ]
)
data class UploadItemEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,
    @ColumnInfo(name = "uri")
    val uri: String,
    @ColumnInfo(name = "display_name")
    val displayName: String?,
    @ColumnInfo(name = "size")
    val size: Long,
    @ColumnInfo(name = "state")
    val state: String,
    @ColumnInfo(name = "last_error_kind")
    val lastErrorKind: String?,
    @ColumnInfo(name = "http_code")
    val httpCode: Int?,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long
)
