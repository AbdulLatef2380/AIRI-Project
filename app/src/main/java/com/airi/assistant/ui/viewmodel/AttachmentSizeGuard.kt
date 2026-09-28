package com.airi.assistant.ui.viewmodel

internal class AttachmentSizeLimitException(
    val dispatchFailure: AttachmentDispatchFailure,
) : IllegalStateException("Attachment exceeds its permitted byte limit")

/** Copies without ever writing bytes beyond the caller-provided attachment limit. */
internal fun copyAttachmentBounded(
    input: java.io.InputStream,
    output: java.io.OutputStream,
    maxBytes: Long,
    tooLargeFailure: AttachmentDispatchFailure,
) {
    require(maxBytes >= 0L)
    val buffer = ByteArray(8 * 1024)
    var copiedBytes = 0L
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        if (count == 0) continue
        if (copiedBytes + count > maxBytes) throw AttachmentSizeLimitException(tooLargeFailure)
        output.write(buffer, 0, count)
        copiedBytes += count
    }
}
