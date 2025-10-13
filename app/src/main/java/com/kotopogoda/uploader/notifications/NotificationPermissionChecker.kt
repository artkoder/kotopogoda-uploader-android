package com.kotopogoda.uploader.notifications

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.kotopogoda.uploader.core.settings.NotificationPermissionProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

@Singleton
class NotificationPermissionChecker @Inject constructor(
    @ApplicationContext private val context: Context,
) : NotificationPermissionProvider {

    private val permissionState = MutableStateFlow(isPermissionGranted())

    override fun permissionFlow(): Flow<Boolean> = permissionState.asStateFlow()

    fun canPostNotifications(): Boolean = permissionState.value

    fun refresh() {
        permissionState.value = isPermissionGranted()
    }

    private fun isPermissionGranted(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }
}
