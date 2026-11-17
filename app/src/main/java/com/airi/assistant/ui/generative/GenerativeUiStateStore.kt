package com.airi.assistant.ui.generative

/**
 * Conversation-scoped state for interactive GenUI blocks.
 *
 * The store deliberately keeps only bounded, user-entered UI state. It does not
 * retain compiled documents or action payloads, and it can therefore be owned by
 * ChatViewModel without retaining provider responses or secrets.
 */
class GenerativeUiStateStore {
    data class State(
        val selectedIndex: Int = -1,
        val selectedNodeId: String? = null,
        val formValues: Map<String, String> = emptyMap(),
        val submitted: Boolean = false,
        val checkedItems: Map<String, Boolean> = emptyMap(),
    )

    private val states = LinkedHashMap<String, State>()

    @Synchronized
    fun state(key: String): State = states[key].orEmptyState()

    @Synchronized
    fun update(key: String, transform: (State) -> State): State {
        val next = transform(states[key].orEmptyState()).bounded()
        states[key] = next
        while (states.size > MAX_KEYS) states.remove(states.keys.first())
        return next
    }

    @Synchronized
    fun clear() = states.clear()

    private fun State?.orEmptyState(): State = this ?: State()

    private fun State.bounded(): State = copy(
        formValues = formValues.entries
            .take(MAX_FORM_FIELDS)
            .associate { it.key.take(MAX_KEY_CHARS) to it.value.take(MAX_VALUE_CHARS) },
        checkedItems = checkedItems.entries
            .take(MAX_CHECKED_ITEMS)
            .associate { it.key.take(MAX_KEY_CHARS) to it.value },
    )

    private companion object {
        const val MAX_KEYS = 256
        const val MAX_FORM_FIELDS = 32
        const val MAX_CHECKED_ITEMS = 64
        const val MAX_KEY_CHARS = 96
        const val MAX_VALUE_CHARS = 500
    }
}
