package com.airi.assistant.memory

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

import com.airi.assistant.memory.entity.BehaviorStatsEntity
import com.airi.assistant.memory.entity.ContextCacheEntity
import com.airi.assistant.memory.entity.MemoryEntities
import com.airi.assistant.memory.entity.UsageStatEntity
import com.airi.assistant.memory.dao.MemoryDao

@Database(
    entities = [
        BehaviorStatsEntity::class,
        ContextCacheEntity::class,
        MemoryEntities::class,
        UsageStatEntity::class
    ],
    version = 1
)
abstract class AiriDatabase : RoomDatabase() {

    abstract fun memoryDao(): MemoryDao

    companion object {

        @Volatile
        private var INSTANCE: AiriDatabase? = null

        fun getDatabase(context: Context): AiriDatabase {
            return INSTANCE ?: synchronized(this) {

                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AiriDatabase::class.java,
                    "airi_memory_db"
                ).build()

                INSTANCE = instance
                instance
            }
        }
    }
}
