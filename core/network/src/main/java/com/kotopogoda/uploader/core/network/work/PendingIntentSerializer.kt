package com.kotopogoda.uploader.core.network.work

import android.app.PendingIntent
import android.os.Parcel

internal object PendingIntentSerializer {
    fun serialize(pendingIntent: PendingIntent): ByteArray {
        val parcel = Parcel.obtain()
        return try {
            pendingIntent.writeToParcel(parcel, 0)
            parcel.marshall()
        } finally {
            parcel.recycle()
        }
    }

    fun deserialize(bytes: ByteArray?): PendingIntent? {
        if (bytes == null || bytes.isEmpty()) {
            return null
        }
        val parcel = Parcel.obtain()
        return try {
            parcel.unmarshall(bytes, 0, bytes.size)
            parcel.setDataPosition(0)
            PendingIntent.CREATOR.createFromParcel(parcel)
        } catch (_: Throwable) {
            null
        } finally {
            parcel.recycle()
        }
    }
}
