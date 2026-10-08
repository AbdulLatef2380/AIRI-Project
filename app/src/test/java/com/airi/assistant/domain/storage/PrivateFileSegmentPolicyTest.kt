package com.airi.assistant.domain.storage

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivateFileSegmentPolicyTest {
    @Test
    fun acceptsNormalSinglePathSegments() {
        assertTrue(PrivateFileSegmentPolicy.isSafeSegment("firebaseUid_123-abc"))
        assertTrue(PrivateFileSegmentPolicy.isSafeSegment("attachment-id"))
    }

    @Test
    fun rejectsTraversalSeparatorsControlCharactersAndOversizedValues() {
        assertFalse(PrivateFileSegmentPolicy.isSafeSegment(""))
        assertFalse(PrivateFileSegmentPolicy.isSafeSegment("."))
        assertFalse(PrivateFileSegmentPolicy.isSafeSegment(".."))
        assertFalse(PrivateFileSegmentPolicy.isSafeSegment("../outside"))
        assertFalse(PrivateFileSegmentPolicy.isSafeSegment("nested/name"))
        assertFalse(PrivateFileSegmentPolicy.isSafeSegment("nested\\name"))
        assertFalse(PrivateFileSegmentPolicy.isSafeSegment("bad\u0000id"))
        assertFalse(PrivateFileSegmentPolicy.isSafeSegment("x".repeat(PrivateFileSegmentPolicy.MAX_SEGMENT_LENGTH + 1)))
    }
}
