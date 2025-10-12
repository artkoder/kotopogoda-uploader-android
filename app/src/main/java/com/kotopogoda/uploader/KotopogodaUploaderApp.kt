package com.kotopogoda.uploader

import android.app.Application
import androidx.work.Configuration
import com.kotopogoda.uploader.notifications.UploadNotif
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class KotopogodaUploaderApp : Application(), Configuration.Provider {

    @Inject
    lateinit var workManagerConfigurationDelegate: Configuration

    override fun onCreate() {
        super.onCreate()
        UploadNotif.ensureChannel(this)
    }

    override val workManagerConfiguration: Configuration
        get() = workManagerConfigurationDelegate
}
