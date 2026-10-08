package com.airi.assistant.ui.viewmodel

internal class AttachmentSizeLimitException(
    val dispatchFailure: AttachmentDispatchFailure,
) : IllegalStateException("Attachment exceeds its permitted byte limit")

/** Enforces an actual-byte ceiling for producers such as Bitmap.compress(). */
internal class BoundedAttachmentOutputStream(
    output: java.io.OutputStream,
    private val maxBytes: Long,
    private val tooLargeFailure: AttachmentDispatchFailure,
) : java.io.FilterOutputStream(output) {
    private var writtenBytes = 0L

    init { require(maxBytes >= 0L) }

    override fun write(value: Int) {
        ensureCapacity(1)
        out.write(value)
        writtenBytes++
    }

    override fun write(buffer: ByteArray, offset: Int, length: Int) {
        ensureCapacity(length)
        out.write(buffer, offset, length)
        writtenBytes += length
    }

    private fun ensureCapacity(additional: Int) {
        if (additional < 0 || writtenBytes + additional > maxBytes) {
            throw AttachmentSizeLimitException(tooLargeFailure)
        }
    }
}

/** Copies without ever writing bytes beyond the caller-provided attachment limit. */
internal fun copyAttachmentBounded(
    input: java.io.InputStream,
    output: java.io.OutputStream,
    maxBytes: Long,
    tooLargeFailure: AttachmentDispatchFailure,
) {
    val buffer = ByteArray(8 * 1024)
    val boundedOutput = BoundedAttachmentOutputStream(output, maxBytes, tooLargeFailure)
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        if (count == 0) continue
        boundedOutput.write(buffer, 0, count)
    }
}
