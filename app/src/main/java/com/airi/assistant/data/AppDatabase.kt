package com.airi.assistant.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [UsageStatEntity::class, ContextCacheEntity::class],
    version = 1
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun usageStatsDao(): UsageStatsDao
    abstract fun contextCacheDao(): ContextCacheDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "airi_database"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}
