package com.kotopogoda.uploader.core.data.di

import android.content.Context
import androidx.room.Room
import com.kotopogoda.uploader.core.data.database.KotopogodaDatabase
import com.kotopogoda.uploader.core.data.folder.FolderDao
import com.kotopogoda.uploader.core.data.folder.FolderRepository
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
    ).build()

    @Provides
    fun provideFolderDao(database: KotopogodaDatabase): FolderDao = database.folderDao()

    @Provides
    @Singleton
    fun provideFolderRepository(folderDao: FolderDao): FolderRepository = FolderRepository(folderDao)
}
