package com.kotopogoda.uploader.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build

object UploadNotif {
    const val CHANNEL_ID: String = "uploads"

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(NotificationManager::class.java)
            val existing = manager.getNotificationChannel(CHANNEL_ID)
            if (existing == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "Загрузки",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Фоновые загрузки фото"
                }
                manager.createNotificationChannel(channel)
            }
        }
    }
}
