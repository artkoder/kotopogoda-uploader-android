package com.kotopogoda.uploader.core.network.upload

import androidx.work.Constraints
import kotlinx.coroutines.flow.StateFlow
interface UploadConstraintsProvider {
    val constraintsState: StateFlow<Constraints?>

    suspend fun awaitConstraints(): Constraints?

    fun buildConstraints(): Constraints

    fun shouldUseExpeditedWork(): Boolean
}
