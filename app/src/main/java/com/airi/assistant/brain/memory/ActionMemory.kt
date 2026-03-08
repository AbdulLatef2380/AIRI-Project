package com.airi.assistant.memory

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity
data class ActionMemory(

    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,

    val screenHash: String,

    val actionType: String,

    val targetText: String?
)
