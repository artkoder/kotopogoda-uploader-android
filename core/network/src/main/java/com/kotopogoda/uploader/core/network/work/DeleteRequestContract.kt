package com.kotopogoda.uploader.core.network.work

internal object DeleteRequestContract {
    const val STATUS_NONE = "none"
    const val STATUS_PENDING = "pending"
    const val STATUS_CONFIRMED = "confirmed"
    const val STATUS_DECLINED = "declined"

    const val KEY_PENDING_DELETE_STATUS = "pendingDeleteStatus"
    const val KEY_PENDING_DELETE_INTENT = "pendingDeleteIntent"
    const val KEY_PENDING_DELETE_LAST_LAUNCH = "pendingDeleteLastLaunch"

    const val EXTRA_WORK_ID = "com.kotopogoda.uploader.core.network.extra.WORK_ID"
    const val EXTRA_PENDING_INTENT = "com.kotopogoda.uploader.core.network.extra.PENDING_INTENT"
    const val REQUEST_CODE_DELETE = 1042
    const val STATE_WORK_ID = "stateWorkId"
    const val STATE_PENDING_INTENT = "statePendingIntent"
    const val DELETE_RELAUNCH_INTERVAL_MILLIS = 5_000L
}
