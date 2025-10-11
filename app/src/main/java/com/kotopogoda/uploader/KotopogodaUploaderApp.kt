package com.kotopogoda.uploader

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import javax.inject.Inject
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class KotopogodaUploaderApp : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()
}
