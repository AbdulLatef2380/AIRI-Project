package com.airi.assistant.memory

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity
data class ScreenNode(

    @PrimaryKey
    val hash: String,

    val appPackage: String,

    val lastSeen: Long

)
