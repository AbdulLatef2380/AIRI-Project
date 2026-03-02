package com.airi.assistant.adaptive

import com.airi.assistant.data.AppDatabase
import com.airi.assistant.data.BehaviorStatsEntity
import kotlinx.coroutines.*

object InteractionTracker {

    private var db: AppDatabase? = null

    fun initialize(database: AppDatabase) {
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

        CoroutineScope(Dispatchers.IO).launch {
            val existing = database.behaviorStatsDao().get(key)
                ?: BehaviorStatsEntity(key = key)

            val updated = transform(existing).copy(
                lastUpdated = System.currentTimeMillis()
            )

            database.behaviorStatsDao().insert(updated)
        }
    }
}
