package com.kotopogoda.uploader.core.data.diagnostics

import com.kotopogoda.uploader.core.logging.diagnostics.FolderSelectionProvider
import com.kotopogoda.uploader.core.logging.diagnostics.UploadQueueSnapshotProvider
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class DiagnosticsModule {

    @Binds
    abstract fun bindUploadQueueSnapshotProvider(
        impl: RepositoryUploadQueueSnapshotProvider,
    ): UploadQueueSnapshotProvider

    @Binds
    abstract fun bindFolderSelectionProvider(
        impl: RepositoryFolderSelectionProvider,
    ): FolderSelectionProvider
}
