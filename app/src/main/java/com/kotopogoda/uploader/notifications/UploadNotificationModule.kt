package com.kotopogoda.uploader.notifications

import com.kotopogoda.uploader.core.network.upload.UploadForegroundDelegate
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class UploadNotificationModule {

    @Binds
    abstract fun bindUploadForegroundDelegate(
        impl: UploadForegroundNotificationDelegate
    ): UploadForegroundDelegate
}
