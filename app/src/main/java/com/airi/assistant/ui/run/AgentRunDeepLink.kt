package com.airi.assistant.ui.run

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Part 5 §1: the long-task notification opens the run screen through `airi://agent/run/{id}`.
 * Parsing is string-based so it stays a plain JVM unit test.
 */
object AgentRunDeepLink {
    private const val SCHEME = "airi"
    private const val HOST = "agent"
    private const val MAX_ID_LENGTH = 128
    private val SAFE_ID = Regex("[A-Za-z0-9._-]+")

    fun build(runId: String): String = "$SCHEME://$HOST/run/$runId"

    /** Returns the run id, or null when the uri is not a well-formed run link. */
    fun parse(uri: String?): String? {
        val raw = uri?.trim().orEmpty()
        val prefix = "$SCHEME://$HOST/run/"
        if (!raw.startsWith(prefix)) return null
        val id = raw.removePrefix(prefix).substringBefore('?').substringBefore('#').trimEnd('/')
        if (id.isEmpty() || id.length > MAX_ID_LENGTH || !SAFE_ID.matches(id)) return null
        return id
    }

    private val _pending = MutableStateFlow<String?>(null)

    /** Run id waiting to be opened by the UI; consumed exactly once via [consume]. */
    val pending: StateFlow<String?> = _pending.asStateFlow()

    fun offer(uri: String?) {
        parse(uri)?.let { _pending.value = it }
    }

    fun consume(runId: String) {
        _pending.compareAndSet(runId, null)
    }
}
