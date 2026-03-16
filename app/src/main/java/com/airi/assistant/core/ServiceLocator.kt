package com.airi.assistant.core

import android.content.Context
import com.airi.assistant.memory.repository.MemoryManager
import com.airi.assistant.world.WorldStateManager
import com.airi.assistant.tools.ToolRegistry

object ServiceLocator {
    lateinit var context: Context

    val memoryManager by lazy { MemoryManager() }
    val worldStateManager by lazy { WorldStateManager() }
    val toolRegistry by lazy { ToolRegistry() }
}
