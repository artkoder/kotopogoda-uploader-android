package com.kotopogoda.uploader.core.data.upload

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "upload_items",
    indices = [
        Index(value = ["state"]),
        Index(value = ["created_at"]),
        Index(value = ["unique_name"], unique = true)
    ]
)
data class UploadItemEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,
    @ColumnInfo(name = "unique_name")
    val uniqueName: String,
    @ColumnInfo(name = "uri")
    val uri: String,
    @ColumnInfo(name = "idempotency_key")
    val idempotencyKey: String,
    @ColumnInfo(name = "display_name")
    val displayName: String,
    @ColumnInfo(name = "state")
    val state: String,
    @ColumnInfo(name = "error_kind")
    val errorKind: String?,
    @ColumnInfo(name = "error_http_code")
    val errorHttpCode: Int?,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
)
