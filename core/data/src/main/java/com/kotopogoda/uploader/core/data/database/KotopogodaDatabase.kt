package com.kotopogoda.uploader.core.data.database

import androidx.room.Database
import androidx.room.RoomDatabase
import com.kotopogoda.uploader.core.data.folder.FolderDao
import com.kotopogoda.uploader.core.data.folder.FolderEntity

@Database(
    entities = [FolderEntity::class],
    version = 1,
    exportSchema = false
)
abstract class KotopogodaDatabase : RoomDatabase() {
    abstract fun folderDao(): FolderDao
}
