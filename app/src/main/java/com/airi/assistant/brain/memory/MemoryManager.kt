package com.airi.assistant.brain.memory

import android.content.Context
import androidx.room.Room

object MemoryManager {

    private var db: AiriDatabase? = null

    fun init(context: Context) {

        db = Room.databaseBuilder(
            context,
            AiriDatabase::class.java,
            "airi_memory.db"
        ).build()
    }

    fun dao(): AiriDao {
        return db!!.airiDao()
    }
}
