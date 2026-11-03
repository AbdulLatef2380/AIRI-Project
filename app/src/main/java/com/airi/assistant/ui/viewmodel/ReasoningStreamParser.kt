package com.airi.assistant.ui.viewmodel

/**
 * Incrementally separates provider reasoning blocks from the answer stream.
 * Delimiters may be split across transport chunks.
 *
 * An opening tag that is never closed (a reply cut off by a length/time limit, or a literal
 * "<think>" used inside prose or code) must not erase the rest of the reply. While streaming the
 * unclosed block stays hidden, but it is retained and released by [finish] as plain text, so a
 * persisted answer is never silently truncated at the tag (report item 17).
 */
class ReasoningStreamParser {
    private val pending = StringBuilder()
    private var inReasoning = false
    /** Opening tag plus everything consumed since, kept only until the block closes. */
    private val heldReasoning = StringBuilder()

    fun consume(chunk: String): String {
        if (chunk.isEmpty()) return ""
        pending.append(chunk)
        return drain(flush = false)
    }

    fun finish(): String = drain(flush = true)

    private fun drain(flush: Boolean): String {
        val visible = StringBuilder()
        while (pending.isNotEmpty()) {
            if (inReasoning) {
                val close = findTag(pending, CLOSE_TAGS)
                if (close == null) {
                    if (flush) {
                        heldReasoning.append(pending)
                        pending.clear()
                    } else {
                        retainTailForSplitTag()
                    }
                    break
                }
                pending.delete(0, close.end)
                heldReasoning.clear()
                inReasoning = false
                continue
            }

            val open = findTag(pending, OPEN_TAGS)
            if (open != null) {
                visible.append(pending.substring(0, open.start))
                heldReasoning.clear()
                heldReasoning.append(pending, open.start, open.end)
                pending.delete(0, open.end)
                inReasoning = true
                continue
            }

            if (flush) {
                visible.append(pending)
                pending.clear()
            } else {
                val safeLength = pending.length - maxTagPrefixLength(pending, ALL_TAGS)
                if (safeLength <= 0) break
                visible.append(pending.substring(0, safeLength))
                pending.delete(0, safeLength)
            }
        }
        if (flush && inReasoning) {
            // Unclosed block at end of stream: release it instead of dropping it (item 17).
            visible.append(heldReasoning)
            heldReasoning.clear()
            inReasoning = false
        }
        return visible.toString()
    }

    private fun retainTailForSplitTag() {
        val keep = maxTagPrefixLength(pending, CLOSE_TAGS)
        val moved = pending.length - keep
        if (moved > 0) {
            heldReasoning.append(pending, 0, moved)
            pending.delete(0, moved)
        }
    }

    private data class TagMatch(val start: Int, val end: Int)

    private fun findTag(source: CharSequence, tags: List<String>): TagMatch? {
        var best: TagMatch? = null
        for (tag in tags) {
            val index = source.indexOf(tag)
            if (index >= 0 && (best == null || index < best!!.start)) {
                best = TagMatch(index, index + tag.length)
            }
        }
        return best
    }

    private fun maxTagPrefixLength(source: CharSequence, tags: List<String>): Int {
        var max = 0
        for (tag in tags) {
            val limit = minOf(source.length, tag.length - 1)
            for (length in 1..limit) {
                if (source.takeLast(length) == tag.take(length)) max = maxOf(max, length)
            }
        }
        return max
    }

    companion object {
        private val OPEN_TAGS = listOf("<think>", "<analysis>", "<reasoning>")
        private val CLOSE_TAGS = listOf("</think>", "</analysis>", "</reasoning>")
        private val ALL_TAGS = OPEN_TAGS + CLOSE_TAGS

        fun extractAnswer(text: String): String {
            val parser = ReasoningStreamParser()
            return (parser.consume(text) + parser.finish()).trim()
        }
    }
}
