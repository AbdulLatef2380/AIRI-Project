package com.airi.assistant.ui.text

/**
 * Explicit action scope for assistant content. UI actions must target this
 * object rather than infer scope from the visible position or raw text.
 */
sealed interface ContentTarget {
    val messageId: String
    val source: String
    val kind: ContentUnitKind?

    val copyPayload: String
        get() = source

    val sharePayload: String
        get() = source

    data class Message(
        override val messageId: String,
        override val source: String,
    ) : ContentTarget {
        override val kind: ContentUnitKind? = null
    }

    data class Block(
        override val messageId: String,
        val blockId: String,
        override val kind: ContentUnitKind,
        override val source: String,
        val language: String? = null,
        val complete: Boolean = true,
        val actions: Set<ContentUnitAction> = emptySet(),
    ) : ContentTarget {
        override val copyPayload: String
            get() = when (kind) {
                ContentUnitKind.TABLE -> AdaptiveContentPolicy.tableClipboardText(source)
                else -> source
            }

        override val sharePayload: String
            get() = when (kind) {
                ContentUnitKind.TABLE -> AdaptiveContentPolicy.tableShareText(source)
                else -> source
            }
    }
}

fun AssistantContentUnit.toContentTarget(messageId: String): ContentTarget.Block =
    ContentTarget.Block(
        messageId = messageId,
        blockId = id,
        kind = kind,
        source = source,
        language = language,
        complete = complete,
        actions = actions,
    )
