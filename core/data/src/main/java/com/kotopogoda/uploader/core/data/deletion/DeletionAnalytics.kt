package com.kotopogoda.uploader.core.data.deletion

import javax.inject.Inject

interface DeletionAnalytics {
    fun deletionEnqueued(count: Int, reason: String)
    fun deletionConfirmed(count: Int, freedBytes: Long)
    fun deletionCancelled(batchId: String)
    fun deletionFailed(count: Int, cause: String?)
    fun autoDeleteSettingChanged(enabled: Boolean)
}

class NoOpDeletionAnalytics @Inject constructor() : DeletionAnalytics {
    override fun deletionEnqueued(count: Int, reason: String) = Unit
    override fun deletionConfirmed(count: Int, freedBytes: Long) = Unit
    override fun deletionCancelled(batchId: String) = Unit
    override fun deletionFailed(count: Int, cause: String?) = Unit
    override fun autoDeleteSettingChanged(enabled: Boolean) = Unit
}
