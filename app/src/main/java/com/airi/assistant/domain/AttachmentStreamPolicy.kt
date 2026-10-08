package com.airi.assistant.domain

import java.io.InputStream
import java.io.OutputStream

/** Copies attachment bytes with a hard cap, including sources that lie about size. */
object AttachmentStreamPolicy {
    class LimitExceeded(val limit: Long) : IllegalArgumentException("attachment exceeds $limit bytes")

    fun copyBounded(input: InputStream, output: OutputStream, limit: Long): Long {
        require(limit >= 0) { "limit must be non-negative" }
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (read == 0) continue
            total += read
            if (total > limit) throw LimitExceeded(limit)
            output.write(buffer, 0, read)
        }
        return total
    }
}
