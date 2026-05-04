package com.airi.assistant.memory.repository

import android.content.Context
import com.airi.assistant.memory.AiriDatabase
import com.airi.assistant.memory.entity.ContextCacheEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Lightweight screen-context cache — persists recent accessibility screen
 * captures to Room for context-aware planning and intent resolution.
 *
 * Thread-safety:
 *   A previous implementation created `CoroutineScope(Dispatchers.IO)` inside
 *   [saveContext] on every call — this leaked one scope per save (no Job
 *   reference → scope never cancelled). The fix: one supervised object-level
 *   scope for the lifetime of the singleton.
 */
object ContextEngine {

    /**
     * Single supervised scope for all background DB writes.
     * SupervisorJob means a failed insert does not cancel pending siblings.
     */
    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var db: AiriDatabase? = null

    fun initialize(context: Context) {
        db = AiriDatabase.getDatabase(context)
    }

    fun saveContext(screenText: String, sourceApp: String, detectedIntent: String) {
        val database = db ?: return

        engineScope.launch {
            database.contextCacheDao().insert(
                ContextCacheEntity(
                    screenText     = screenText.take(1500),
                    sourceApp      = sourceApp,
                    detectedIntent = detectedIntent,
                    timestamp      = System.currentTimeMillis()
                )
            )

            // Remove context older than 10 minutes to cap table growth.
            val expire = System.currentTimeMillis() - (10 * 60 * 1000)
            database.contextCacheDao().cleanupOld(expire)
        }
    }

    suspend fun getRecentContext(): ContextCacheEntity? {
        val database = db ?: return null
        val threshold = System.currentTimeMillis() - (5 * 60 * 1000)
        return database.contextCacheDao().getRecentContext(threshold)
    }
}
