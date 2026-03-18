package com.airi.assistant.core

import android.content.Context
import com.airi.assistant.memory.repository.MemoryManager
import com.airi.assistant.world.WorldStateManager
import com.airi.assistant.tools.ToolRegistry

object ServiceLocator {

    private lateinit var appContext: Context

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    val memoryManager: MemoryManager by lazy {
        MemoryManager(appContext)
    }

    val worldStateManager: WorldStateManager by lazy {
        WorldStateManager(appContext)
    }

    val toolRegistry: ToolRegistry by lazy {
        ToolRegistry
    }
}
