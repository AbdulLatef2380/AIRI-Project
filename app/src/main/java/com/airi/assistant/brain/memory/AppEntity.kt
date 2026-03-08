package com.airi.assistant.brain.memory

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "apps")
data class AppEntity(

    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,

    val packageName: String,

    val appName: String
)
