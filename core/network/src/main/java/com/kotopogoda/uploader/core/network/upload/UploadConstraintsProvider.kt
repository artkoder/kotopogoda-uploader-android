package com.kotopogoda.uploader.core.network.upload

import androidx.work.Constraints

fun interface UploadConstraintsProvider {
    fun buildConstraints(): Constraints
}
