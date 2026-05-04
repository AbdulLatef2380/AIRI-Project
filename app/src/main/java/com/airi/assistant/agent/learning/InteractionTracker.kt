package com.airi.assistant.agent.learning

import com.airi.assistant.memory.AiriDatabase
import com.airi.assistant.memory.entity.BehaviorStatsEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Lightweight interaction recorder — persists shown/accepted/dismissed counts
 * to the Room [AiriDatabase] for adaptive-behavior and outcome-scoring feeds.
 *
 * Thread-safety:
 *   All DB writes are dispatched to the object-level [trackerScope].
 *   A previous implementation created `CoroutineScope(Dispatchers.IO)` inside
 *   [update] on every call — this leaked one scope per invocation (no Job
 *   reference → scope never cancelled, GC eventually collected but untracked).
 *   The fix: one supervised scope for the lifetime of the object.
 */
object InteractionTracker {

    /**
     * Single supervised scope for all background DB writes.
     * SupervisorJob ensures that a failed DB insert does not cancel sibling
     * writes, while the scope itself is never orphaned.
     */
    private val trackerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var db: AiriDatabase? = null

    fun initialize(database: AiriDatabase) {
        db = database
    }

    fun recordShown(app: String, intent: String) {
        update(app, intent) { it.copy(shownCount = it.shownCount + 1) }
    }

    fun recordAccepted(app: String, intent: String) {
        update(app, intent) { it.copy(acceptedCount = it.acceptedCount + 1) }
    }

    fun recordDismissed(app: String, intent: String) {
        update(app, intent) { it.copy(dismissedCount = it.dismissedCount + 1) }
    }

    private fun update(
        app: String,
        intent: String,
        transform: (BehaviorStatsEntity) -> BehaviorStatsEntity
    ) {
        val database = db ?: return
        val key = "$app|$intent"

        trackerScope.launch {
            val existing = database.behaviorStatsDao().get(key)
                ?: BehaviorStatsEntity(key = key)

            val updated = transform(existing).copy(
                lastUpdated = System.currentTimeMillis()
            )

            database.behaviorStatsDao().insert(updated)
        }
    }
}
