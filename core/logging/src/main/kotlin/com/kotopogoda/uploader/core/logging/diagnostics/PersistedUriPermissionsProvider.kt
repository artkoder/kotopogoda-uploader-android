package com.kotopogoda.uploader.core.logging.diagnostics

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

data class PersistedUriPermissionSnapshot(
    val uri: String,
    val read: Boolean,
    val write: Boolean,
    val persistedTime: Long,
)

interface PersistedUriPermissionsProvider {
    fun getPersistedPermissions(): List<PersistedUriPermissionSnapshot>
}

@Singleton
class ContentResolverPersistedUriPermissionsProvider @Inject constructor(
    @ApplicationContext private val context: Context,
) : PersistedUriPermissionsProvider {
    override fun getPersistedPermissions(): List<PersistedUriPermissionSnapshot> {
        return context.contentResolver.persistedUriPermissions.map { permission ->
            PersistedUriPermissionSnapshot(
                uri = permission.uri.toString(),
                read = permission.isReadPermission,
                write = permission.isWritePermission,
                persistedTime = permission.persistedTime,
            )
        }
    }
}
