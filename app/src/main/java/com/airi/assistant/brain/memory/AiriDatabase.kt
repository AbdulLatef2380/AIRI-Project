package com.airi.assistant.brain.memory

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        AppEntity::class,
        ScreenEntity::class,
        UINodeEntity::class
    ],
    version = 1
)
abstract class AiriDatabase : RoomDatabase() {

    abstract fun airiDao(): AiriDao
}
