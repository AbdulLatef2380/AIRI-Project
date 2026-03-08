package com.airi.assistant.memory

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        ScreenNode::class,
        UIElement::class,
        ActionMemory::class
    ],
    version = 1
)
abstract class AIRIDatabase : RoomDatabase() {

    abstract fun memoryDao(): MemoryDao

}
