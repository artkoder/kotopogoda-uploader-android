package com.kotopogoda.uploader.core.work

import androidx.work.Constraints
import androidx.work.NetworkType
import com.kotopogoda.uploader.core.network.upload.UploadConstraintsProvider
import com.kotopogoda.uploader.core.settings.WifiOnlyUploadsFlow
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn

@Singleton
class UploadConstraintsHelper @Inject constructor(
    @WifiOnlyUploadsFlow wifiOnlyUploadsFlow: Flow<Boolean>,
) : UploadConstraintsProvider {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val wifiOnlyState = wifiOnlyUploadsFlow.stateIn(
        scope = scope,
        started = SharingStarted.Eagerly,
        initialValue = false,
    )

    override fun buildConstraints(): Constraints {
        val requiredNetworkType = if (wifiOnlyState.value) {
            NetworkType.UNMETERED
        } else {
            NetworkType.CONNECTED
        }
        return Constraints.Builder()
            .setRequiredNetworkType(requiredNetworkType)
            .build()
    }
}
