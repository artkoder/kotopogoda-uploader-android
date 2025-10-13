package com.kotopogoda.uploader.core.settings

import kotlinx.coroutines.flow.Flow

interface NotificationPermissionProvider {
    fun permissionFlow(): Flow<Boolean>
}
