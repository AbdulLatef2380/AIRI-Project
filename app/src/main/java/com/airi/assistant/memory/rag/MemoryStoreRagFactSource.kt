package com.airi.assistant.memory.rag

import android.content.Context
import com.airi.assistant.ai.prompt.MemoryStore
import com.airi.assistant.ai.prompt.ScopedMemoryFact

/** Reads only the facts MemoryStore authorizes for this exact chat context. */
class MemoryStoreRagFactSource(context: Context) {
    private val appContext = context.applicationContext

    fun getScopedFacts(
        sessionId: String,
        projectId: String,
        maxPrivacyLevel: Int,
        limit: Int,
    ): List<ScopedMemoryFact> = MemoryStore.getScopedFacts(
        ctx = appContext,
        sessionId = sessionId,
        projectId = projectId,
        maxPrivacyLevel = maxPrivacyLevel,
        limit = limit,
    ).filter { it.sourceMemoryId != null }
}
