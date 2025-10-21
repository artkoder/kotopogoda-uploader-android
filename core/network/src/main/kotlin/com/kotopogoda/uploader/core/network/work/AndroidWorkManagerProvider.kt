package com.kotopogoda.uploader.core.network.work

import android.content.Context
import androidx.work.WorkManager
import com.kotopogoda.uploader.core.data.upload.UploadLog
import com.kotopogoda.uploader.core.work.WorkManagerProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import timber.log.Timber

@Singleton
class AndroidWorkManagerProvider @Inject constructor(
    @ApplicationContext private val context: Context,
) : WorkManagerProvider {

    private val workManager: WorkManager by lazy {
        Timber.tag("WorkManager").i(
            UploadLog.message(
                category = "WORK/Factory",
                action = "lazy_get",
            ),
        )
        WorkManager.getInstance(context)
    }

    override fun get(): WorkManager = workManager
}
