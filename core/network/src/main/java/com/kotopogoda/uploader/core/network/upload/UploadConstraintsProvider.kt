package com.kotopogoda.uploader.core.network.upload

import androidx.work.Constraints

interface UploadConstraintsProvider {
    fun buildConstraints(): Constraints

    fun shouldUseExpeditedWork(): Boolean
}
