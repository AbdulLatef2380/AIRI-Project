package com.airi.assistant.accessibility.service

import android.content.Context
import android.util.Log
import com.airi.assistant.memory.AiriDatabase
import com.airi.assistant.memory.entity.UsageStatEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * BehaviorEngine — tracks suggestion usage and ranks suggestions by popularity.
 *
 * ── ANR FIX ──────────────────────────────────────────────────────────────────
 * The previous implementation called `runBlocking` inside
 * `adjustSuggestionPriority`, which blocks the calling thread (often the
 * accessibility service thread or main thread) until the Room query completes.
 * This is a guaranteed ANR source.
 *
 * Fix: ranking data is loaded eagerly in the background and cached in
 * [usageCache]. [adjustSuggestionPriority] reads only from the in-memory cache
 * (zero blocking) and refreshes the cache asynchronously after each read.
 * The first call before any cache load returns suggestions in original order
 * (safe fallback — no data loss, no ANR).
 */
object BehaviorEngine {

    private const val TAG = "BehaviorEngine"

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var database: AiriDatabase? = null

    /** In-memory cache: suggestion → usage count. Populated asynchronously. */
    @Volatile
    private var usageCache: Map<String, Int> = emptyMap()

    fun initialize(context: Context) {
        database = AiriDatabase.getDatabase(context)
        refreshCache()
    }

    fun recordUsage(suggestion: String) {
        val db = database ?: return
        scope.launch {
            try {
                val dao = db.usageStatsDao()
                val existing = dao.getAll().find { it.featureName == suggestion }
                if (existing == null) {
                    dao.insert(
                        UsageStatEntity(
                            featureName       = suggestion,
                            usageCount        = 1,
                            lastUsedTimestamp = System.currentTimeMillis()
                        )
                    )
                } else {
                    dao.incrementUsage(suggestion, System.currentTimeMillis())
                }
                refreshCache()
            } catch (e: Exception) {
                Log.e(TAG, "recordUsage failed: ${e.message}")
            }
        }
    }

    /**
     * Sort [suggestions] by historical usage count.
     *
     * NON-BLOCKING — reads only from [usageCache] (in-memory).
     * Triggers an async cache refresh so subsequent calls get fresher data.
     */
    fun adjustSuggestionPriority(suggestions: List<String>): List<String> {
        val cache = usageCache
        // Trigger background refresh so the NEXT call gets fresh data
        refreshCache()
        return if (cache.isEmpty()) {
            suggestions
        } else {
            suggestions.sortedByDescending { cache[it] ?: 0 }
        }
    }

    private fun refreshCache() {
        val db = database ?: return
        scope.launch {
            try {
                val stats = db.usageStatsDao().getAll()
                usageCache = stats.associate { it.featureName to it.usageCount }
            } catch (e: Exception) {
                Log.e(TAG, "cache refresh failed: ${e.message}")
            }
        }
    }
}
