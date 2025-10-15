package com.kotopogoda.uploader.core.network.work

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.work.Data
import androidx.work.WorkManager
import java.util.UUID

internal class DeleteRequestHelperActivity : Activity() {

    private var workId: UUID? = null
    private var pendingIntentBytes: ByteArray? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            val extras = intent?.extras
            val idString = extras?.getString(DeleteRequestContract.EXTRA_WORK_ID)
            val bytes = extras?.getByteArray(DeleteRequestContract.EXTRA_PENDING_INTENT)
            val parsedId = runCatching { idString?.let(UUID::fromString) }.getOrNull()
            if (parsedId == null || bytes == null) {
                finish()
                return
            }
            workId = parsedId
            pendingIntentBytes = bytes
            launchDeleteRequest(bytes)
        } else {
            workId = savedInstanceState.getString(DeleteRequestContract.STATE_WORK_ID)?.let(UUID::fromString)
            pendingIntentBytes = savedInstanceState.getByteArray(DeleteRequestContract.STATE_PENDING_INTENT)
            pendingIntentBytes?.let(::launchDeleteRequest)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != DeleteRequestContract.REQUEST_CODE_DELETE) {
            return
        }
        val status = when (resultCode) {
            RESULT_OK -> DeleteRequestContract.STATUS_CONFIRMED
            RESULT_CANCELED -> DeleteRequestContract.STATUS_DECLINED
            else -> DeleteRequestContract.STATUS_NONE
        }
        updateProgress(status)
        finish()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(DeleteRequestContract.STATE_WORK_ID, workId?.toString())
        pendingIntentBytes?.let { outState.putByteArray(DeleteRequestContract.STATE_PENDING_INTENT, it) }
    }

    private fun launchDeleteRequest(bytes: ByteArray) {
        val pendingIntent = PendingIntentSerializer.deserialize(bytes)
        if (pendingIntent == null) {
            updateProgress(DeleteRequestContract.STATUS_NONE)
            finish()
            return
        }
        updateProgress(DeleteRequestContract.STATUS_PENDING)
        runCatching {
            startIntentSenderForResult(
                pendingIntent.intentSender,
                DeleteRequestContract.REQUEST_CODE_DELETE,
                null,
                0,
                0,
                0
            )
        }.onFailure {
            updateProgress(DeleteRequestContract.STATUS_NONE)
            finish()
        }
    }

    private fun updateProgress(status: String) {
        val id = workId ?: return
        val builder = Data.Builder()
            .putString(DeleteRequestContract.KEY_PENDING_DELETE_STATUS, status)
        if (status == DeleteRequestContract.STATUS_PENDING) {
            val bytes = pendingIntentBytes
            if (bytes != null) {
                builder.putByteArray(DeleteRequestContract.KEY_PENDING_DELETE_INTENT, bytes)
                builder.putLong(DeleteRequestContract.KEY_PENDING_DELETE_LAST_LAUNCH, System.currentTimeMillis())
            }
        } else {
            builder.putByteArray(DeleteRequestContract.KEY_PENDING_DELETE_INTENT, ByteArray(0))
            builder.putLong(DeleteRequestContract.KEY_PENDING_DELETE_LAST_LAUNCH, 0L)
        }
        updateProgress(id, builder)
    }

    private fun updateProgress(id: UUID, builder: Data.Builder) {
        WorkManager.getInstance(applicationContext).setProgressAsync(id, builder.build())
    }

    companion object {
        fun launch(context: Context, workId: UUID, pendingIntentBytes: ByteArray) {
            val intent = Intent(context, DeleteRequestHelperActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(DeleteRequestContract.EXTRA_WORK_ID, workId.toString())
                .putExtra(DeleteRequestContract.EXTRA_PENDING_INTENT, pendingIntentBytes)
            context.startActivity(intent)
        }
    }
}
