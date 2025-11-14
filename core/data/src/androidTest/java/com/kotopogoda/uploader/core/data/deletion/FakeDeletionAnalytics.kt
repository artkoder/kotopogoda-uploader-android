package com.kotopogoda.uploader.core.data.deletion

class FakeDeletionAnalytics : DeletionAnalytics {
    private val _events = mutableListOf<DeletionEvent>()
    val events: List<DeletionEvent> get() = _events

    override fun deletionEnqueued(count: Int, reason: String) {
        _events.add(DeletionEvent.Enqueued(count, reason))
    }

    override fun deletionConfirmed(count: Int, freedBytes: Long) {
        _events.add(DeletionEvent.Confirmed(count, freedBytes))
    }

    override fun deletionCancelled(batchId: String) {
        _events.add(DeletionEvent.Cancelled(batchId))
    }

    override fun deletionFailed(count: Int, cause: String?) {
        _events.add(DeletionEvent.Failed(count, cause))
    }

    override fun autoDeleteSettingChanged(enabled: Boolean) {
        _events.add(DeletionEvent.AutoDeleteSettingChanged(enabled))
    }

    fun clear() {
        _events.clear()
    }

    sealed interface DeletionEvent {
        data class Enqueued(val count: Int, val reason: String) : DeletionEvent
        data class Confirmed(val count: Int, val freedBytes: Long) : DeletionEvent
        data class Cancelled(val batchId: String) : DeletionEvent
        data class Failed(val count: Int, val cause: String?) : DeletionEvent
        data class AutoDeleteSettingChanged(val enabled: Boolean) : DeletionEvent
    }
}
