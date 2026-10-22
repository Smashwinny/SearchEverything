package com.search.everything.files

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.search.everything.apps.AppLocation
import com.search.everything.apps.AppLocationDao

@Database(entities = [FileEntry::class, AppLocation::class], version = 2, exportSchema = false)
abstract class FileDatabase : RoomDatabase() {
    abstract fun fileDao(): FileDao
    abstract fun appLocationDao(): AppLocationDao

    companion object {
        @Volatile private var instance: FileDatabase? = null

        fun get(context: Context): FileDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    FileDatabase::class.java,
                    "file_index.db"
                )
                    // 两个表都是可随时重建的索引，升级直接推倒重来
                    .fallbackToDestructiveMigration()
                    .build().also { instance = it }
            }
    }
}
