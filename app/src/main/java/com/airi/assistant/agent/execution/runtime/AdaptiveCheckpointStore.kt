package com.airi.assistant.agent.execution.runtime

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * AdaptiveCheckpointStore — in-memory + JSON-serializable checkpoint store
 * for [AdaptiveGraphEngine] long-running task continuation.
 *
 * In production this would persist to Room via a DAO.
 * The current implementation uses an in-memory map with JSON export
 * so checkpoints survive ViewModel recreation within a process lifetime.
 */
class AdaptiveCheckpointStore {
    private val store = ConcurrentHashMap<String, AdaptiveCheckpoint>()
    private val TAG   = "AdaptiveCheckpointStore"

    fun save(checkpoint: AdaptiveCheckpoint) {
        store[checkpoint.planIntent] = checkpoint
        Log.d(TAG, "Checkpoint saved: intent='${checkpoint.planIntent.take(40)}' completed=${checkpoint.completedNodeIds.size}")
    }

    fun load(planIntent: String): AdaptiveCheckpoint? = store[planIntent]

    fun clear(planIntent: String) { store.remove(planIntent) }

    fun clearAll() { store.clear() }

    /** Serialize to JSON for debugging / diagnostics. */
    fun toJson(): String {
        val arr = JSONArray()
        store.values.forEach { cp ->
            arr.put(JSONObject().apply {
                put("planIntent",     cp.planIntent)
                put("completedCount", cp.completedNodeIds.size)
                put("timestampMs",    cp.timestampMs)
            })
        }
        return JSONObject().apply { put("checkpoints", arr) }.toString(2)
    }
}

data class AdaptiveCheckpoint(
    val planIntent:       String,
    val completedNodeIds: Set<String>,
    val timestampMs:      Long = System.currentTimeMillis()
)
