package com.airi.assistant.brain.memory

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "ui_nodes")
data class UINodeEntity(

    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,

    val screenId: Int,

    val text: String?,

    val resourceId: String?,

    val bounds: String?
)
