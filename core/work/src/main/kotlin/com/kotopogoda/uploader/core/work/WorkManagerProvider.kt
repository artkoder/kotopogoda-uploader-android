package com.kotopogoda.uploader.core.work

import androidx.work.WorkManager

fun interface WorkManagerProvider {
    fun get(): WorkManager
}
