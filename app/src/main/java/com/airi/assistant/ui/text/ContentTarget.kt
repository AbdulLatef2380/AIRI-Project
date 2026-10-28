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
        val actions: Set<ContentUnitAction> = setOf(ContentUnitAction.COPY, ContentUnitAction.SHARE),
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

    /** Tool output is a separate presentation target, never an agent-output block. */
    data class ToolOutput(
        override val messageId: String,
        val outputId: String,
        val toolName: String,
        val step: Int,
        override val source: String,
        val truncated: Boolean = false,
        val actions: Set<ContentUnitAction> = emptySet(),
    ) : ContentTarget {
        override val kind: ContentUnitKind? = null
    }
}

/** Single enforcement point for every ARPE content action. */
object ContentActionPolicy {
    fun allows(target: ContentTarget, action: ContentUnitAction): Boolean = when (target) {
        is ContentTarget.Message -> action in target.actions
        is ContentTarget.Block -> action in target.actions &&
            (action != ContentUnitAction.SAVE || target.complete)
        is ContentTarget.ToolOutput -> action in target.actions
    }
}

fun ContentTarget.canPerform(action: ContentUnitAction): Boolean =
    ContentActionPolicy.allows(this, action)

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

fun AssistantToolOutput.toContentTarget(messageId: String): ContentTarget.ToolOutput =
    ContentTarget.ToolOutput(
        messageId = messageId,
        outputId = id,
        toolName = toolName,
        step = step,
        source = text,
        truncated = truncated,
        actions = actions,
    )
