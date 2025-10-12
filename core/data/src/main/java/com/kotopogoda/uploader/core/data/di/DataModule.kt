package com.kotopogoda.uploader.core.data.di

import android.content.Context
import androidx.room.Room
import com.kotopogoda.uploader.core.data.database.KotopogodaDatabase
import com.kotopogoda.uploader.core.data.folder.FolderDao
import com.kotopogoda.uploader.core.data.folder.FolderRepository
import com.kotopogoda.uploader.core.data.indexer.IndexerRepository
import com.kotopogoda.uploader.core.data.photo.PhotoDao
import com.kotopogoda.uploader.core.data.photo.PhotoRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
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
        KotopogodaDatabase.MIGRATION_2_3
    ).build()

    @Provides
    fun provideFolderDao(database: KotopogodaDatabase): FolderDao = database.folderDao()

    @Provides
    fun providePhotoDao(database: KotopogodaDatabase): PhotoDao = database.photoDao()

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
    fun providePhotoRepository(photoDao: PhotoDao): PhotoRepository = PhotoRepository(photoDao)
}
