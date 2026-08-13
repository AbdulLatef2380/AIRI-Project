package com.airi.assistant.agent.workspace

import android.util.Log
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * AgentWorkspace — shared, typed, session-scoped artifact store for multi-turn tool use.
 *
 * PROBLEM SOLVED:
 *   Without a workspace, tool results are only accessible as raw strings in
 *   [SubAgentContext.dependencyResults]. A file path downloaded by CloudBrowserAgent
 *   is invisible to agent unless the orchestrator manually threads
 *   the string through every intermediate context copy.
 *
 * REAL EXECUTION:
 *   1. Any agent in the plan calls [put] to publish a typed artifact
 *      (TEXT, FILE_PATH, JSON_BLOB, BINARY_REF) under a logical [key].
 *   2. Any downstream agent reads it via [get], [getText], [getPath], or [getJson].
 *   3. [link] declares a formal data-flow edge: "task A produces KEY, task B consumes it."
 *      The orchestrator uses [resolveDependency] to inject the artifact value into the
 *      downstream task's input before execution.
 *   4. [snapshot] returns an immutable copy — safe to log or display in the
 *      Observability screen.
 *
 * THREAD SAFETY:
 *   All mutations are via [ConcurrentHashMap] — safe for parallel task execution.
 *
 * LIFECYCLE:
 *   One workspace per [OrchestratorPlan] execution. Created by
 *   [ProductionAgentOrchestrator.executePlan] and passed through [SubAgentContext]
 *   via the [workspaceRef] extension property. Discarded when the plan completes.
 *
 * WIRING:
 *   - [ServiceLocator] creates the workspace per-plan (NOT a singleton).
 *   - [ProductionAgentOrchestrator.executePlan] creates a [AgentWorkspace],
 *     passes it to each task via [SubAgentContext.dependencyResults] under key
 *     "__workspace_ref" (or via a field added to SubAgentContext).
 *   - Agents access it via the companion factory or a constructor-injected reference.
 */
class AgentWorkspace(
    val workspaceId: String = UUID.randomUUID().toString()
) {

    companion object {
        private const val TAG          = "AgentWorkspace"
        private const val MAX_ENTRIES  = 200
        private const val MAX_TEXT_LEN = 100_000  // 100 KB text cap per artifact
    }

    // ── Artifact storage ─────────────────────────────────────────────────────

    private val artifacts = ConcurrentHashMap<String, WorkspaceArtifact>()

    // ── Data flow edges: producerTaskId+key → consumerTaskId ─────────────────

    private val dataFlowEdges = ConcurrentHashMap<String, MutableList<DataFlowEdge>>()

    // ── Write API ─────────────────────────────────────────────────────────────

    /**
     * Store a text artifact under [key].
     * Overwrites any existing artifact with the same key.
     */
    fun putText(key: String, text: String, producerTaskId: String = "") {
        checkCapacity()
        artifacts[key] = WorkspaceArtifact(
            key           = key,
            type          = ArtifactType.TEXT,
            textValue     = text.take(MAX_TEXT_LEN),
            producerTaskId = producerTaskId
        )
        if (com.airi.assistant.BuildConfig.DEBUG) Log.d(TAG, "PUT TEXT key=$key len=${text.length} producer=$producerTaskId ws=$workspaceId")
    }

    /**
     * Store a file path artifact under [key].
     * Used by agent and CloudBrowserAgent to hand off
     * downloaded/generated files to downstream agents.
     */
    fun putPath(key: String, path: String, producerTaskId: String = "") {
        checkCapacity()
        artifacts[key] = WorkspaceArtifact(
            key            = key,
            type           = ArtifactType.FILE_PATH,
            textValue      = path,
            producerTaskId = producerTaskId
        )
        if (com.airi.assistant.BuildConfig.DEBUG) Log.d(TAG, "PUT PATH key=$key path=$path producer=$producerTaskId ws=$workspaceId")
    }

    /**
     * Store a JSON blob artifact under [key].
     */
    fun putJson(key: String, json: String, producerTaskId: String = "") {
        checkCapacity()
        artifacts[key] = WorkspaceArtifact(
            key            = key,
            type           = ArtifactType.JSON_BLOB,
            textValue      = json.take(MAX_TEXT_LEN),
            producerTaskId = producerTaskId
        )
        if (com.airi.assistant.BuildConfig.DEBUG) Log.d(TAG, "PUT JSON key=$key len=${json.length} producer=$producerTaskId ws=$workspaceId")
    }

    /**
     * Store a binary reference (e.g. content URI, Room row ID) under [key].
     */
    fun putRef(key: String, ref: String, producerTaskId: String = "") {
        checkCapacity()
        artifacts[key] = WorkspaceArtifact(
            key            = key,
            type           = ArtifactType.BINARY_REF,
            textValue      = ref,
            producerTaskId = producerTaskId
        )
        if (com.airi.assistant.BuildConfig.DEBUG) Log.d(TAG, "PUT REF key=$key ref=$ref producer=$producerTaskId ws=$workspaceId")
    }

    // ── Read API ──────────────────────────────────────────────────────────────

    /** Retrieve a raw [WorkspaceArtifact], or null if [key] is not set. */
    fun get(key: String): WorkspaceArtifact? = artifacts[key]

    /** Retrieve the text value of a TEXT artifact, or null. */
    fun getText(key: String): String? =
        artifacts[key]?.takeIf { it.type == ArtifactType.TEXT }?.textValue

    /** Retrieve the path value of a FILE_PATH artifact, or null. */
    fun getPath(key: String): String? =
        artifacts[key]?.takeIf { it.type == ArtifactType.FILE_PATH }?.textValue

    /** Retrieve the JSON blob of a JSON_BLOB artifact, or null. */
    fun getJson(key: String): String? =
        artifacts[key]?.takeIf { it.type == ArtifactType.JSON_BLOB }?.textValue

    /** Retrieve any artifact value as a string regardless of type. */
    fun getRaw(key: String): String? = artifacts[key]?.textValue

    /** True if [key] exists in the workspace. */
    fun has(key: String): Boolean = artifacts.containsKey(key)

    /** All currently stored keys. */
    fun keys(): Set<String> = artifacts.keys.toSet()

    // ── Data-flow API ─────────────────────────────────────────────────────────

    /**
     * Declare that [producerTaskId] writes [key] and [consumerTaskId] will read it.
     * The orchestrator can use this to auto-inject the value into the consumer's input.
     */
    fun link(producerTaskId: String, key: String, consumerTaskId: String) {
        val edgeKey = "${producerTaskId}::$key"
        dataFlowEdges.getOrPut(edgeKey) { mutableListOf() }.add(
            DataFlowEdge(producerTaskId, key, consumerTaskId)
        )
        if (com.airi.assistant.BuildConfig.DEBUG) Log.d(TAG, "LINK $producerTaskId[$key] → $consumerTaskId ws=$workspaceId")
    }

    /**
     * Resolve all data-flow edges for [consumerTaskId] — returns a map of
     * [artifactKey → value] that the orchestrator can inject into the task input.
     */
    fun resolveDependency(consumerTaskId: String): Map<String, String> {
        val resolved = mutableMapOf<String, String>()
        dataFlowEdges.forEach { (_, edges) ->
            edges.filter { it.consumerTaskId == consumerTaskId }.forEach { edge ->
                val value = getRaw(edge.artifactKey)
                if (value != null) {
                    resolved[edge.artifactKey] = value
                    if (com.airi.assistant.BuildConfig.DEBUG) Log.d(TAG, "WORKSPACE_DEPENDENCY_RESOLVED consumer=$consumerTaskId artifact=${edge.artifactKey} valueChars=${value.length}")
                }
            }
        }
        return resolved
    }

    // ── Observability ─────────────────────────────────────────────────────────

    /** Immutable snapshot of all artifacts — safe to log or display. */
    fun snapshot(): WorkspaceSnapshot = WorkspaceSnapshot(
        workspaceId = workspaceId,
        artifacts   = artifacts.values.map {
            it.copy(textValue = it.textValue?.take(200))   // truncate for display
        }.toList(),
        edgeCount   = dataFlowEdges.values.sumOf { it.size }
    )

    // ── Housekeeping ─────────────────────────────────────────────────────────

    private fun checkCapacity() {
        if (artifacts.size >= MAX_ENTRIES) {
            val oldest = artifacts.values.minByOrNull { it.createdAtMs }
            oldest?.let {
                artifacts.remove(it.key)
                Log.w(TAG, "Workspace capacity reached — evicted oldest artifact: ${it.key}")
            }
        }
    }

    /** Clear all artifacts (call when the plan is complete). */
    fun clear() {
        artifacts.clear()
        dataFlowEdges.clear()
        if (com.airi.assistant.BuildConfig.DEBUG) Log.d(TAG, "Workspace $workspaceId cleared")
    }
}

// ── Domain types ───────────────────────────────────────────────────────────────

enum class ArtifactType { TEXT, FILE_PATH, JSON_BLOB, BINARY_REF }

data class WorkspaceArtifact(
    val key:            String,
    val type:           ArtifactType,
    val textValue:      String?,
    val producerTaskId: String   = "",
    val createdAtMs:    Long     = System.currentTimeMillis()
)

data class DataFlowEdge(
    val producerTaskId: String,
    val artifactKey:    String,
    val consumerTaskId: String
)

data class WorkspaceSnapshot(
    val workspaceId: String,
    val artifacts:   List<WorkspaceArtifact>,
    val edgeCount:   Int
)
