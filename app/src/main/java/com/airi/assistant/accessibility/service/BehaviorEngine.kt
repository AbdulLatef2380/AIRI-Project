package com.airi.assistant.accessibility.service

import android.content.Context
import com.airi.assistant.memory.AiriDatabase
import com.airi.assistant.memory.entity.UsageStatEntity
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.*

object BehaviorEngine : CoroutineScope {

    override val coroutineContext: CoroutineContext
        get() = Dispatchers.IO + SupervisorJob()

    private var database: AiriDatabase? = null

    fun initialize(context: Context) {
        database = AiriDatabase.getDatabase(context)
    }

    fun recordUsage(suggestion: String) {
        val db = database ?: return

        launch {
            val dao = db.usageStatsDao()
            // ✅ تم التغيير من suggestionText إلى featureName
            val existing = dao.getAll().find { it.featureName == suggestion }

            if (existing == null) {
                dao.insert(
                    UsageStatEntity(
                        featureName = suggestion, // ✅ تم التعديل هنا أيضاً
                        usageCount = 1,
                        lastUsedTimestamp = System.currentTimeMillis()
                    )
                )
            } else {
                dao.incrementUsage(
                    suggestion,
                    System.currentTimeMillis()
                )
            }
        }
    }

    fun adjustSuggestionPriority(suggestions: List<String>): List<String> {
        val db = database ?: return suggestions

        var ranked = suggestions

        runBlocking {
            val stats = db.usageStatsDao().getAll()
            ranked = suggestions.sortedByDescending { suggestion ->
                // ✅ تم التغيير من suggestionText إلى featureName
                stats.find { it.featureName == suggestion }?.usageCount ?: 0
            }
        }

        return ranked
    }
}
