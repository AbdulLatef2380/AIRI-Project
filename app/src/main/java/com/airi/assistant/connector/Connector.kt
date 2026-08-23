package com.airi.assistant.connector

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Connector — the unit of capability the agent can call.
 *
 * Replaces the legacy [com.airi.assistant.integration.Integration] interface
 * with a coroutine-first contract that fits the rest of the agent stack
 * (which is `suspend`-shaped end-to-end). Every external surface the agent
 * can act through — a remote LLM endpoint, an Android Intent, a file, an
 * MCP server — is a Connector.
 *
 * Implementations MUST be safe to keep as singletons inside
 * [ConnectorRegistry]; per-call state belongs in [ConnectorInput] /
 * [ConnectorOutput].
 *
 * Lifecycle:
 *  - [connect] is idempotent. It validates credentials and warms up
 *    long-lived resources (HTTP clients, sessions). Calling it twice is a
 *    no-op; calling it on an already-connected connector returns the
 *    existing state.
 *  - [disconnect] tears down credentials and resources. After disconnect,
 *    [execute] MUST fail with [ConnectorOutput.Failure] of code
 *    `not_connected` rather than throwing — the agent inspects the result.
 *  - [execute] is the only call site that performs work. It is suspending
 *    and cancellation-safe.
 */
interface Connector {
    /** Stable, machine-readable id (e.g. "openai_chat", "android_intent"). */
    val id: String

    /** Human-readable name shown in the UI. */
    val name: String

    /** One-line description for the connector picker. */
    val description: String

    /** Bucket the UI groups this connector under. */
    val type: ConnectorType

    /** Snapshot metadata for rendering. Cheap to call. */
    fun meta(): ConnectorMeta

    /** Reactive state. Re-emits whenever connection / health changes.
     *
     *  Returns [StateFlow] (not just [Flow]) so synchronous UI projections
     *  — e.g. [com.airi.assistant.ui.viewmodel.ConnectorsViewModel] — can
     *  read the current value without collecting the flow. Every concrete
     *  connector backs this with a [kotlinx.coroutines.flow.MutableStateFlow]
     *  so the contract holds in practice; lifting the interface to match
     *  removes a round-trip cast at every call site. */
    fun state(): StateFlow<ConnectorState>

    /** Validate creds and prepare resources. Idempotent. */
    suspend fun connect(): ConnectorState

    /** Tear down. Idempotent. */
    suspend fun disconnect()

    /** Execute a single agent action. Must NOT throw on user-visible
     *  errors — return [ConnectorOutput.Failure] instead. */
    suspend fun execute(input: ConnectorInput): ConnectorOutput
}

/**
 * The four buckets the UI presents as tabs (API / Apps / MCP / System) plus
 * a LOCAL bucket for on-device capabilities (intents, files, voice).
 *
 * Adding a value here requires updating [ConnectorTab] in the UI layer
 * and the bucket-routing rules in [AgentRouter.tabFor].
 */
enum class ConnectorType {
    /** Cloud LLMs, REST APIs, webhooks. */
    API,

    /** Third-party app integrations (Gmail, GitHub, Telegram, …). */
    APP,

    /** On-device capabilities (Android Intents, files, voice/mtmd). */
    LOCAL,

    /** Model Context Protocol servers (extensible plugin surface). */
    MCP,

    /** OS / device-state surfaces (battery, network, sensors). */
    SYSTEM,
}

/**
 * Cheap metadata snapshot. Used by the UI to render tiles without
 * subscribing to the connector's state flow.
 */
data class ConnectorMeta(
    val id: String,
    val name: String,
    val description: String,
    val type: ConnectorType,
    /** Optional URL the UI can use to fetch a favicon (or null to fall
     *  back to the type's default vector icon). */
    val iconUrl: String? = null,
    /** Optional tags for search / categorization. */
    val tags: List<String> = emptyList(),
)

/**
 * Reactive state. Producers MUST be conservative about changes — emit only
 * when something user-visible changes, since this drives Compose recompose.
 */
data class ConnectorState(
    val connected: Boolean,
    val healthy: Boolean = connected,
    /** Free-form short status (e.g. "Connected as @octocat"). */
    val statusLine: String = "",
    val lastUpdatedMs: Long = 0L,
    val errorMessage: String? = null,
)

/**
 * Generic input envelope. The agent constructs one of these from the
 * routed intent + payload. Each connector documents which `action` strings
 * it understands; unknown actions MUST return [ConnectorOutput.Failure]
 * with code `unknown_action`.
 */
data class ConnectorInput(
    val action: String,
    val text: String = "",
    val params: Map<String, String> = emptyMap(),
    /** Opaque binary payload (e.g. audio bytes for the voice connector). */
    val binary: ByteArray? = null,
    /** Execution ownership for actions that may pause awaiting a user decision. */
    val execution: ConnectorExecutionContext? = null,
) {
    // Manual equals/hashCode because of the ByteArray field — auto-generated
    // data class equals would compare the array by reference, which is
    // wrong and a common source of "why doesn't my cache hit" bugs.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ConnectorInput) return false
        if (action != other.action) return false
        if (text   != other.text)   return false
        if (params != other.params) return false
        if (execution != other.execution) return false
        if (binary == null) return other.binary == null
        if (other.binary == null) return false
        return binary.contentEquals(other.binary)
    }
    override fun hashCode(): Int {
        var r = action.hashCode()
        r = 31 * r + text.hashCode()
        r = 31 * r + params.hashCode()
        r = 31 * r + (execution?.hashCode() ?: 0)
        r = 31 * r + (binary?.contentHashCode() ?: 0)
        return r
    }
}

/**
 * Stable ownership carried from a product task into a connector side effect.
 * A connector may accept an approved continuation only when every coordinate
 * matches its durable record; unscoped actions cannot request durable resume.
 */
data class ConnectorExecutionContext(
    val projectId: String?,
    val taskId: String,
    val missionId: String,
    val runId: String,
    val stepId: String,
    val idempotencyKey: String,
    val continuationId: String? = null
) {
    val isComplete: Boolean
        get() = taskId.isNotBlank() && missionId.isNotBlank() && runId.isNotBlank() &&
            stepId.isNotBlank() && idempotencyKey.isNotBlank()
}

/**
 * Sealed result hierarchy. Every connector returns one of these — the
 * agent never has to wrap calls in try/catch for the common failure modes.
 */
sealed class ConnectorOutput {
    data class Success(
        val text: String,
        val data: Map<String, String> = emptyMap(),
        val durationMs: Long = 0L,
    ) : ConnectorOutput()

    data class Failure(
        /** Stable machine-readable error code (e.g. `not_connected`,
         *  `unknown_action`, `rate_limited`, `network_error`, `timeout`). */
        val code: String,
        val message: String,
        /** Whether the agent should retry this same connector or fall back. */
        val retryable: Boolean = false,
    ) : ConnectorOutput()

    /** A durable side effect was stopped before invocation and needs a decision. */
    data class ApprovalRequired(
        val approvalId: String,
        val taskId: String,
        val runId: String,
        val stepId: String,
        val expiresAtMs: Long,
        val message: String
    ) : ConnectorOutput()

    /** Streaming output — for chat-style connectors. The collector
     *  receives partial chunks until the flow completes. */
    data class Streaming(
        val chunks: Flow<String>,
    ) : ConnectorOutput()
}
