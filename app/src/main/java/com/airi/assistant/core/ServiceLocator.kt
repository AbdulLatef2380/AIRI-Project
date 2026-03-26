package com.airi.assistant.core

import android.content.Context
import com.airi.assistant.memory.repository.MemoryManager
import com.airi.assistant.world.WorldStateManager
import com.airi.assistant.tools.ToolRegistry

object ServiceLocator {

    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    val memoryManager: MemoryManager by lazy {
        MemoryManager(requireNotNull(appContext))
    }

    val worldStateManager: WorldStateManager by lazy {
        WorldStateManager(requireNotNull(appContext))
    }

    val toolRegistry: ToolRegistry
        get() = ToolRegistry
}
