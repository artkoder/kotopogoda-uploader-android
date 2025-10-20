package com.kotopogoda.uploader.core.logging.diagnostics

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class LoggingDiagnosticsModule {

    @Binds
    abstract fun bindDiagnosticsProvider(impl: EnvironmentDiagnosticsProvider): DiagnosticsProvider

    @Binds
    abstract fun bindUploadQueueSnapshotProvider(impl: RepositoryUploadQueueSnapshotProvider): UploadQueueSnapshotProvider

    @Binds
    abstract fun bindFolderSelectionProvider(impl: RepositoryFolderSelectionProvider): FolderSelectionProvider

    @Binds
    abstract fun bindWorkInfoProvider(impl: WorkManagerWorkInfoProvider): WorkInfoProvider

    @Binds
    abstract fun bindMediaStoreSnapshotProvider(impl: ContentResolverMediaStoreSnapshotProvider): MediaStoreSnapshotProvider

    @Binds
    abstract fun bindPersistedUriPermissionsProvider(
        impl: ContentResolverPersistedUriPermissionsProvider,
    ): PersistedUriPermissionsProvider
}
