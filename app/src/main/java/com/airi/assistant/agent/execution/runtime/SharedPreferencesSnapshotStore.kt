package com.airi.assistant.agent.execution.runtime

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * Lightweight snapshot persistence backed by SharedPreferences.
 *
 * Each plan is stored as a JSON blob keyed by planId.  The store is
 * intentionally simple — it is append-only within a session and evicts
 * old entries when the total count exceeds [maxEntries].
 */
class SharedPreferencesSnapshotStore(context: Context) : ExecutionSnapshotStore {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val tag = "SnapshotStore"

    override fun save(snapshot: ExecutionGraphSnapshot) {
        try {
            val json = snapshot.toJson().toString()
            prefs.edit()
                .putString(snapshot.planId, json)
                .apply()
            evictIfNeeded()
        } catch (e: Exception) {
            Log.w(tag, "Failed to persist snapshot ${snapshot.planId}: ${e.message}")
        }
    }

    override fun load(planId: String): ExecutionGraphSnapshot? {
        return try {
            val raw = prefs.getString(planId, null) ?: return null
            JSONObject(raw).toSnapshot()
        } catch (e: Exception) {
            Log.w(tag, "Failed to load snapshot $planId: ${e.message}")
            null
        }
    }

    private fun evictIfNeeded() {
        val all = prefs.all
        if (all.size > maxEntries) {
            // Remove the oldest half by just deleting by key order (best-effort)
            val toRemove = all.keys.take(all.size - maxEntries)
            val edit = prefs.edit()
            toRemove.forEach { edit.remove(it) }
            edit.apply()
        }
    }

    // ── JSON serialisation ────────────────────────────────────────────────────

    private fun ExecutionGraphSnapshot.toJson(): JSONObject = JSONObject().apply {
        put("planId", planId)
        put("planIntent", planIntent)
        put("executionState", executionState.name)
        put("activeNodeId", activeNodeId ?: JSONObject.NULL)
        put("updatedAtMs", updatedAtMs)
        put("completedNodeIds", JSONArray(completedNodeIds))
        put("failedNodeIds", JSONArray(failedNodeIds))
        put("reflectionNotes", JSONArray(reflectionNotes))
        put("nodes", JSONArray(nodes.map { it.toJson() }))
    }

    private fun ExecutionNode.toJson(): JSONObject = JSONObject().apply {
        put("nodeId", nodeId)
        put("executionState", executionState.name)
        put("assignedAgent", assignedAgent ?: JSONObject.NULL)
        put("retryCount", retryCount)
        put("timeoutMs", timeoutMs)
        put("dependencies", JSONArray(dependencies))
        put("structuredOutputs", JSONObject(structuredOutputs))
        put("producedArtifacts", JSONArray(producedArtifacts.map { it.toJson() }))
    }

    private fun ExecutionArtifact.toJson(): JSONObject = JSONObject().apply {
        put("type", type)
        put("value", value.take(4_000))
        put("sourceNodeId", sourceNodeId)
        put("timestampMs", timestampMs)
    }

    private fun JSONObject.toSnapshot(): ExecutionGraphSnapshot {
        val nodes = getJSONArray("nodes").let { arr ->
            (0 until arr.length()).map { arr.getJSONObject(it).toNode() }
        }
        val completed = getJSONArray("completedNodeIds").let { arr ->
            (0 until arr.length()).map { arr.getString(it) }
        }
        val failed = getJSONArray("failedNodeIds").let { arr ->
            (0 until arr.length()).map { arr.getString(it) }
        }
        val notes = getJSONArray("reflectionNotes").let { arr ->
            (0 until arr.length()).map { arr.getString(it) }
        }
        return ExecutionGraphSnapshot(
            planId = getString("planId"),
            planIntent = getString("planIntent"),
            nodes = nodes,
            completedNodeIds = completed,
            failedNodeIds = failed,
            activeNodeId = optString("activeNodeId").takeIf { it.isNotEmpty() && it != "null" },
            executionState = runCatching {
                PlanExecutionState.valueOf(getString("executionState"))
            }.getOrDefault(PlanExecutionState.CREATED),
            reflectionNotes = notes,
            updatedAtMs = getLong("updatedAtMs")
        )
    }

    private fun JSONObject.toNode(): ExecutionNode {
        val deps = getJSONArray("dependencies").let { arr ->
            (0 until arr.length()).map { arr.getString(it) }
        }
        val outputs = getJSONObject("structuredOutputs").let { obj ->
            obj.keys().asSequence().associateWith { obj.getString(it) }
        }
        val artifacts = getJSONArray("producedArtifacts").let { arr ->
            (0 until arr.length()).map { arr.getJSONObject(it).toArtifact() }
        }
        return ExecutionNode(
            nodeId = getString("nodeId"),
            dependencies = deps,
            assignedAgent = optString("assignedAgent").takeIf { it.isNotEmpty() && it != "null" },
            executionState = runCatching {
                PlanExecutionState.valueOf(getString("executionState"))
            }.getOrDefault(PlanExecutionState.CREATED),
            retryCount = optInt("retryCount", 0),
            timeoutMs = optLong("timeoutMs", 30_000L),
            structuredOutputs = outputs,
            producedArtifacts = artifacts
        )
    }

    private fun JSONObject.toArtifact() = ExecutionArtifact(
        type = getString("type"),
        value = getString("value"),
        sourceNodeId = getString("sourceNodeId"),
        timestampMs = getLong("timestampMs")
    )

    companion object {
        private const val PREFS_NAME = "airi_execution_snapshots"
        private const val maxEntries = 50
    }
}
