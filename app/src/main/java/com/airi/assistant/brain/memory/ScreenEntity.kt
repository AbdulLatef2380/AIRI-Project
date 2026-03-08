package com.airi.assistant.brain.memory

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "screens")
data class ScreenEntity(

    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,

    val appId: Int,

    val screenHash: String
)
