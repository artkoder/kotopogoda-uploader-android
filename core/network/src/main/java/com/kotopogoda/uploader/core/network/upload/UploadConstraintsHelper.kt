package com.kotopogoda.uploader.core.network.upload

import androidx.work.Constraints
import androidx.work.NetworkType
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

@Singleton
class UploadConstraintsHelper @Inject constructor(
) : UploadConstraintsProvider {

    private val constraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()
    private val constraintsStateInternal = MutableStateFlow<Constraints?>(constraints)

    override val constraintsState: StateFlow<Constraints?> = constraintsStateInternal

    override suspend fun awaitConstraints(): Constraints? {
        return constraintsStateInternal.value
    }

    override fun buildConstraints(): Constraints {
        return constraintsStateInternal.value ?: constraints.also {
            constraintsStateInternal.value = it
        }
    }

    override fun shouldUseExpeditedWork(): Boolean {
        return true
    }
}
