package com.airi.assistant.memory

import android.content.Context
import androidx.room.Room

object MemoryManager {

    private lateinit var db: AIRIDatabase
    private lateinit var dao: MemoryDao

    fun init(context: Context) {

        db = Room.databaseBuilder(
            context,
            AIRIDatabase::class.java,
            "airi_memory"
        ).build()

        dao = db.memoryDao()
    }

    suspend fun getAction(hash: String): ActionMemory? {
        return dao.getAction(hash)
    }

    suspend fun learn(hash: String, action: String, target: String?) {

        dao.insertAction(
            ActionMemory(
                screenHash = hash,
                actionType = action,
                targetText = target
            )
        )
    }
}
