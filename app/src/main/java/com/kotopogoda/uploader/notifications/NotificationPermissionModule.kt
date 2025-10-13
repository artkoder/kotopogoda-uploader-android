package com.kotopogoda.uploader.notifications

import com.kotopogoda.uploader.core.settings.NotificationPermissionProvider
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class NotificationPermissionModule {

    @Binds
    @Singleton
    abstract fun bindNotificationPermissionProvider(
        checker: NotificationPermissionChecker,
    ): NotificationPermissionProvider
}
