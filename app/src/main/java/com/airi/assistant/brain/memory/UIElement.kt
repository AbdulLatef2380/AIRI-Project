package com.airi.assistant.memory

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity
data class UIElement(

    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,

    val screenHash: String,

    val text: String?,

    val viewId: String?,

    val className: String?,

    val clickable: Boolean
)
