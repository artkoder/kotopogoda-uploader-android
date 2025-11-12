package com.kotopogoda.uploader.core.data.deletion

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "deletion_queue",
    indices = [
        Index(value = ["status", "is_uploading"])
    ]
)
data class DeletionItem(
    @PrimaryKey
    @ColumnInfo(name = "media_id")
    val mediaId: Long,
    @ColumnInfo(name = "content_uri")
    val contentUri: String,
    @ColumnInfo(name = "display_name")
    val displayName: String?,
    @ColumnInfo(name = "size_bytes")
    val sizeBytes: Long?,
    @ColumnInfo(name = "date_taken")
    val dateTaken: Long?,
    @ColumnInfo(name = "reason")
    val reason: String,
    @ColumnInfo(name = "status", defaultValue = "'pending'")
    val status: String = DeletionItemStatus.PENDING,
    @ColumnInfo(name = "is_uploading", defaultValue = "0")
    val isUploading: Boolean = false,
    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis()
)
