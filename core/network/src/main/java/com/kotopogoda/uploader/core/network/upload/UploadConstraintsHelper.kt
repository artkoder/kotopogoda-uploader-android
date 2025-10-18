package com.kotopogoda.uploader.core.network.upload

import androidx.work.Constraints
import androidx.work.NetworkType
import com.kotopogoda.uploader.core.settings.WifiOnlyUploadsFlow
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

@Singleton
class UploadConstraintsHelper @Inject constructor(
    @WifiOnlyUploadsFlow wifiOnlyUploadsFlow: Flow<Boolean>,
) : UploadConstraintsProvider {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val wifiOnlyState = MutableStateFlow<Boolean?>(null)
    private val constraintsStateInternal = MutableStateFlow<Constraints?>(null)

    override val wifiOnlyUploadsState: StateFlow<Boolean?> = wifiOnlyState.asStateFlow()

    override val constraintsState: StateFlow<Constraints?> = constraintsStateInternal.asStateFlow()

    init {
        wifiOnlyUploadsFlow
            .onEach { value ->
                wifiOnlyState.value = value
                constraintsStateInternal.value = buildConstraints(value)
            }
            .launchIn(scope)
    }

    override suspend fun awaitConstraints(): Constraints? {
        constraintsStateInternal.value?.let { return it }
        val wifiOnly = wifiOnlyUploadsState.value ?: wifiOnlyUploadsState.filterNotNull().first()
        val constraints = buildConstraints(wifiOnly)
        constraintsStateInternal.value = constraints
        return constraints
    }

    override fun buildConstraints(): Constraints {
        constraintsStateInternal.value?.let { return it }
        val useWifiOnly = wifiOnlyState.value
            ?: error("Wi-Fi preference not loaded yet")
        return buildConstraints(useWifiOnly).also { constraintsStateInternal.value = it }
    }

    private fun buildConstraints(useWifiOnly: Boolean): Constraints {
        val requiredNetworkType = if (useWifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED
        return Constraints.Builder()
            .setRequiredNetworkType(requiredNetworkType)
            .build()
    }

    override fun shouldUseExpeditedWork(): Boolean {
        return wifiOnlyState.value?.not() ?: false
    }
}
