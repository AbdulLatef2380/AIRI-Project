package com.airi.assistant.ui.viewmodel

/**
 * Incrementally separates provider reasoning blocks from the answer stream.
 * Delimiters may be split across transport chunks.
 */
class ReasoningStreamParser {
    private val pending = StringBuilder()
    private var inReasoning = false

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
                    if (flush) pending.clear()
                    else retainTailForSplitTag()
                    break
                }
                pending.delete(0, close.end)
                inReasoning = false
                continue
            }

            val open = findTag(pending, OPEN_TAGS)
            if (open != null) {
                visible.append(pending.substring(0, open.start))
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
        return visible.toString()
    }

    private fun retainTailForSplitTag() {
        val keep = maxTagPrefixLength(pending, CLOSE_TAGS)
        if (keep == 0) {
            pending.clear()
        } else if (pending.length > keep) {
            pending.delete(0, pending.length - keep)
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
