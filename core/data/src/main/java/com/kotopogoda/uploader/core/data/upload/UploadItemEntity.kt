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
    @ColumnInfo(name = "idempotency_key", defaultValue = "''")
    val idempotencyKey: String = "",
    @ColumnInfo(name = "uri", defaultValue = "''")
    val uri: String = "",
    @ColumnInfo(name = "display_name", defaultValue = "'photo.jpg'")
    val displayName: String = "photo.jpg",
    @ColumnInfo(name = "size", defaultValue = "0")
    val size: Long = 0,
    @ColumnInfo(name = "enhanced", defaultValue = "0")
    val enhanced: Boolean = false,
    @ColumnInfo(name = "enhance_strength")
    val enhanceStrength: Float? = null,
    @ColumnInfo(name = "enhance_delegate")
    val enhanceDelegate: String? = null,
    @ColumnInfo(name = "enhance_metrics_l_mean")
    val enhanceMetricsLMean: Float? = null,
    @ColumnInfo(name = "enhance_metrics_p_dark")
    val enhanceMetricsPDark: Float? = null,
    @ColumnInfo(name = "enhance_metrics_b_sharpness")
    val enhanceMetricsBSharpness: Float? = null,
    @ColumnInfo(name = "enhance_metrics_n_noise")
    val enhanceMetricsNNoise: Float? = null,
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
    @ColumnInfo(name = "last_error_message")
    val lastErrorMessage: String? = null,
)
