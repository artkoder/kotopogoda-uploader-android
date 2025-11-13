package com.kotopogoda.uploader.core.data.di

import android.content.ContentResolver
import android.content.Context
import androidx.room.Room
import com.kotopogoda.uploader.core.data.database.KotopogodaDatabase
import com.kotopogoda.uploader.core.data.deletion.DeletionAnalytics
import com.kotopogoda.uploader.core.data.deletion.DeletionItemDao
import com.kotopogoda.uploader.core.data.deletion.DeletionQueueRepository
import com.kotopogoda.uploader.core.data.folder.FolderDao
import com.kotopogoda.uploader.core.data.folder.FolderRepository
import com.kotopogoda.uploader.core.data.indexer.IndexerRepository
import com.kotopogoda.uploader.core.data.photo.MediaStorePhotoMetadataReader
import com.kotopogoda.uploader.core.data.photo.PhotoDao
import com.kotopogoda.uploader.core.data.photo.PhotoRepository
import com.kotopogoda.uploader.core.data.upload.UploadItemDao
import com.kotopogoda.uploader.core.data.upload.UploadQueueRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.time.Clock
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DataModule {

    @Provides
    @Singleton
    fun provideDatabase(
        @ApplicationContext context: Context
    ): KotopogodaDatabase = Room.databaseBuilder(
        context,
        KotopogodaDatabase::class.java,
        "kotopogoda.db"
    ).addMigrations(
        KotopogodaDatabase.MIGRATION_1_2,
        KotopogodaDatabase.MIGRATION_2_3,
        KotopogodaDatabase.MIGRATION_3_4,
        KotopogodaDatabase.MIGRATION_4_5,
        KotopogodaDatabase.MIGRATION_5_6,
        KotopogodaDatabase.MIGRATION_6_7,
        KotopogodaDatabase.MIGRATION_7_8,
        KotopogodaDatabase.MIGRATION_8_9,
        KotopogodaDatabase.MIGRATION_9_10,
        KotopogodaDatabase.MIGRATION_10_11,
        KotopogodaDatabase.MIGRATION_11_12,
    ).build()

    @Provides
    fun provideContentResolver(@ApplicationContext context: Context): ContentResolver = context.contentResolver

    @Provides
    fun provideFolderDao(database: KotopogodaDatabase): FolderDao = database.folderDao()

    @Provides
    fun providePhotoDao(database: KotopogodaDatabase): PhotoDao = database.photoDao()

    @Provides
    fun provideUploadItemDao(database: KotopogodaDatabase): UploadItemDao = database.uploadItemDao()

    @Provides
    fun provideDeletionItemDao(database: KotopogodaDatabase): DeletionItemDao = database.deletionItemDao()

    @Provides
    @Singleton
    fun provideFolderRepository(folderDao: FolderDao): FolderRepository = FolderRepository(folderDao)

    @Provides
    @Singleton
    fun provideIndexerRepository(
        @ApplicationContext context: Context,
        folderRepository: FolderRepository,
        photoDao: PhotoDao
    ): IndexerRepository = IndexerRepository(
        context = context,
        folderRepository = folderRepository,
        photoDao = photoDao
    )

    @Provides
    @Singleton
    fun providePhotoRepository(
        folderRepository: FolderRepository,
        @ApplicationContext context: Context
    ): PhotoRepository = PhotoRepository(
        folderRepository = folderRepository,
        context = context
    )

    @Provides
    @Singleton
    fun provideMediaStorePhotoMetadataReader(
        @ApplicationContext context: Context,
    ): MediaStorePhotoMetadataReader = MediaStorePhotoMetadataReader(context.contentResolver)

    @Provides
    @Singleton
    fun provideUploadQueueRepository(
        uploadItemDao: UploadItemDao,
        photoDao: PhotoDao,
        metadataReader: MediaStorePhotoMetadataReader,
        @ApplicationContext context: Context,
        clock: Clock,
    ): UploadQueueRepository = UploadQueueRepository(
        uploadItemDao = uploadItemDao,
        photoDao = photoDao,
        metadataReader = metadataReader,
        contentResolver = context.contentResolver,
        clock = clock,
    )

    @Provides
    @Singleton
    fun provideDeletionQueueRepository(
        deletionItemDao: DeletionItemDao,
        clock: Clock,
        deletionAnalytics: DeletionAnalytics,
    ): DeletionQueueRepository = DeletionQueueRepository(
        deletionItemDao = deletionItemDao,
        clock = clock,
        deletionAnalytics = deletionAnalytics,
    )
}
