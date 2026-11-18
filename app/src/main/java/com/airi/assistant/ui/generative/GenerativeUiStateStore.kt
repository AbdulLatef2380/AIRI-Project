package com.airi.assistant.ui.generative

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Conversation-scoped state for interactive GenUI blocks.
 *
 * The store deliberately keeps only bounded, user-entered UI state. It does not
 * retain compiled documents or action payloads, and it can therefore be owned by
 * ChatViewModel without retaining provider responses or secrets.
 */
class GenerativeUiStateStore {
    enum class ActionPhase { STAGED, RUNNING, SUCCEEDED, FAILED }

    data class State(
        val selectedIndex: Int = -1,
        val selectedNodeId: String? = null,
        val formValues: Map<String, String> = emptyMap(),
        val submitted: Boolean = false,
        val checkedItems: Map<String, Boolean> = emptyMap(),
    )

    // Access-ordered: the least recently *used* entry is evicted first, so a form
    // the user is still filling in is never displaced by older, idle state.
    private val states = LinkedHashMap<String, State>(16, 0.75f, true)
    private val actions = LinkedHashMap<String, ActionPhase>(16, 0.75f, true)
    private val _actionVersion = MutableStateFlow(0)
    val actionVersion: StateFlow<Int> = _actionVersion

    @Synchronized
    fun state(key: String): State = states[key].orEmptyState()

    @Synchronized
    fun update(key: String, transform: (State) -> State): State {
        val next = transform(states[key].orEmptyState()).bounded()
        states[key] = next
        while (states.size > MAX_KEYS) {
            val eldest = states.keys.iterator()
            eldest.next()
            eldest.remove()
        }
        return next
    }

    @Synchronized
    fun actionPhase(messageId: String?, actionId: String): ActionPhase? =
        actions[actionKey(messageId, actionId)]

    @Synchronized
    fun setActionPhase(messageId: String?, actionId: String, phase: ActionPhase) {
        actions[actionKey(messageId, actionId)] = phase
        while (actions.size > MAX_ACTIONS) {
            val eldest = actions.keys.iterator()
            eldest.next()
            eldest.remove()
        }
        _actionVersion.value = _actionVersion.value + 1
    }

    /** Drops all state owned by one assistant message (used when it is deleted). */
    @Synchronized
    fun removeMessage(messageId: String) {
        val prefix = messagePrefix(messageId)
        states.keys.removeAll { it.startsWith(prefix) }
        actions.keys.removeAll { it.startsWith(prefix) }
        _actionVersion.value = _actionVersion.value + 1
    }

    /** Drops all state owned by any of [messageIds] (used when a session is deleted or cleared). */
    @Synchronized
    fun removeMessages(messageIds: Collection<String>) {
        if (messageIds.isEmpty()) return
        val prefixes = messageIds.map { messagePrefix(it) }
        states.keys.removeAll { key -> prefixes.any { key.startsWith(it) } }
        actions.keys.removeAll { key -> prefixes.any { key.startsWith(it) } }
        _actionVersion.value = _actionVersion.value + 1
    }

    @Synchronized
    fun clear() {
        states.clear()
        actions.clear()
        _actionVersion.value = _actionVersion.value + 1
    }

    @Synchronized
    fun size(): Int = states.size

    private fun State?.orEmptyState(): State = this ?: State()

    private fun State.bounded(): State = copy(
        formValues = formValues.entries
            .take(MAX_FORM_FIELDS)
            .associate { it.key.take(MAX_KEY_CHARS) to it.value.take(MAX_VALUE_CHARS) },
        checkedItems = checkedItems.entries
            .take(MAX_CHECKED_ITEMS)
            .associate { it.key.take(MAX_KEY_CHARS) to it.value },
    )

    companion object {
        /**
         * The single source of state keys. Every stateful block of every message
         * must use it, so two messages that reuse a block id never share state.
         */
        fun key(messageId: String?, blockId: String): String = messagePrefix(messageId) + blockId

        private fun messagePrefix(messageId: String?): String =
            (messageId?.takeIf { it.isNotBlank() } ?: UNSCOPED) + ":"

        private const val UNSCOPED = "unscoped"
        private const val MAX_KEYS = 256
        private const val MAX_ACTIONS = 128
        private const val MAX_FORM_FIELDS = 32
        private const val MAX_CHECKED_ITEMS = 64
        private const val MAX_KEY_CHARS = 96
        private const val MAX_VALUE_CHARS = 500

        private fun actionKey(messageId: String?, actionId: String): String =
            messagePrefix(messageId) + "action:" + actionId
    }
}
