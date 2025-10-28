package com.kotopogoda.uploader.core.network.work

import android.app.Activity
import android.app.PendingIntent
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import android.os.Handler
import android.os.Looper
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

interface MediaStoreDeleteLauncher {
    suspend fun requestDelete(
        resolver: ContentResolver,
        uri: Uri,
    ): MediaStoreDeleteResult
}

sealed interface MediaStoreDeleteResult {
    data object Success : MediaStoreDeleteResult
    data object Cancelled : MediaStoreDeleteResult
    data class Failure(val throwable: Throwable?) : MediaStoreDeleteResult
}

class RealMediaStoreDeleteLauncher @Inject constructor(
    @ApplicationContext private val context: Context,
) : MediaStoreDeleteLauncher {

    override suspend fun requestDelete(
        resolver: ContentResolver,
        uri: Uri,
    ): MediaStoreDeleteResult {
        val pendingIntent = runCatching {
            MediaStore.createDeleteRequest(resolver, listOf(uri))
        }.getOrElse { error ->
            return MediaStoreDeleteResult.Failure(error)
        }

        val resultCode = try {
            awaitResult(pendingIntent)
        } catch (error: Exception) {
            return MediaStoreDeleteResult.Failure(error)
        }

        return when (resultCode) {
            Activity.RESULT_OK -> MediaStoreDeleteResult.Success
            Activity.RESULT_CANCELED -> MediaStoreDeleteResult.Cancelled
            else -> MediaStoreDeleteResult.Failure(null)
        }
    }

    private suspend fun awaitResult(pendingIntent: PendingIntent): Int = suspendCoroutine { cont ->
        val handler = Looper.getMainLooper()?.let(::Handler)
        val callback = PendingIntent.OnFinished { _, _, resultCode, _, _ ->
            cont.resume(resultCode)
        }
        try {
            val fillInIntent = Intent().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            pendingIntent.send(context, 0, fillInIntent, callback, handler)
        } catch (error: PendingIntent.CanceledException) {
            cont.resumeWithException(error)
        }
    }
}
