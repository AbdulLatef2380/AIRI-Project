package com.airi.assistant.ui.generative

/**
 * Holds the one reviewed GenUI action that has been staged in the composer.
 *
 * The staged action only counts as an interaction when the user submits exactly
 * the text it produced. Editing the composer, switching conversations or
 * submitting anything else drops it, so a stale action can never be attributed
 * to an unrelated message. The class is pure (no Android types) so the full
 * lifecycle is unit-testable.
 */
class PendingGenerativeUiActionGate {
    private var pending: GenerativeUiAction? = null

    @Synchronized
    fun stage(action: GenerativeUiAction) {
        pending = action
    }

    /** Called for every composer edit; a changed text invalidates the staged action. */
    @Synchronized
    fun onComposerTextChanged(text: String) {
        val staged = pending ?: return
        if (staged.prompt != text.trim()) pending = null
    }

    /** Called on session switch, deletion and history clearing. */
    @Synchronized
    fun clear() {
        pending = null
    }

    /**
     * Claims the staged action for a submitted text. The slot is always emptied:
     * a staged action is single-use whether or not it matched.
     */
    @Synchronized
    fun claim(submittedText: String): GenerativeUiAction? {
        val staged = pending
        pending = null
        return staged?.takeIf { it.prompt == submittedText.trim() }
    }

    @Synchronized
    fun hasPending(): Boolean = pending != null
}
