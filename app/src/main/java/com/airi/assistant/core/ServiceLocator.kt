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

    // Expose context property for classes that need it (e.g. UnifiedCognitiveLoop)
    var context: Context?
        get() = appContext
        set(value) {
            if (value != null) appContext = value.applicationContext
        }

    val memoryManager: MemoryManager by lazy {
        MemoryManager(requireNotNull(appContext) { "ServiceLocator not initialized" })
    }

    val worldStateManager: WorldStateManager by lazy {
        WorldStateManager(requireNotNull(appContext) { "ServiceLocator not initialized" })
    }

    val toolRegistry: ToolRegistry
        get() = ToolRegistry
}
