package com.airi.assistant.domain.storage

/** Rejects values that must occupy exactly one component beneath app-private storage. */
object PrivateFileSegmentPolicy {
    const val MAX_SEGMENT_LENGTH = 128

    fun isSafeSegment(value: String): Boolean =
        value.isNotBlank() &&
            value.length <= MAX_SEGMENT_LENGTH &&
            value != "." &&
            value != ".." &&
            '/' !in value &&
            '\\' !in value &&
            value.none { it.isISOControl() }
}
