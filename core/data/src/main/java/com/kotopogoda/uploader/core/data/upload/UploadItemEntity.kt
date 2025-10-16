package com.kotopogoda.uploader.core.data.upload

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "upload_items",
    indices = [
        Index(value = ["state"]),
        Index(value = ["created_at"])
    ]
)
data class UploadItemEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,
    @ColumnInfo(name = "photo_id")
    val photoId: String,
    @ColumnInfo(name = "state")
    val state: String,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long? = null,
    @ColumnInfo(name = "last_error_kind")
    val lastErrorKind: String? = null,
    @ColumnInfo(name = "http_code")
    val httpCode: Int? = null,
)
