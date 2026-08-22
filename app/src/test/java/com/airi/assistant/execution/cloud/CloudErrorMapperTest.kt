package com.airi.assistant.execution.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudErrorMapperTest {

    @Test
    fun genericProviderBodiesNeverAppearInMappedMessage() {
        val privateBody = "request contained api_key=super_secret_value and /data/user/0/com.airi/files/private.txt"

        val invalidRequest = CloudErrorMapper.map(400, privateBody)
        val unknownFailure = CloudErrorMapper.map(418, privateBody)

        assertEquals(CloudErrorType.INVALID_REQUEST, invalidRequest.type)
        assertEquals(CloudErrorType.UNKNOWN, unknownFailure.type)
        assertFalse(invalidRequest.message.contains("super_secret_value"))
        assertFalse(invalidRequest.message.contains("/data/user/0"))
        assertFalse(unknownFailure.message.contains("super_secret_value"))
        assertFalse(unknownFailure.message.contains("/data/user/0"))
    }

    @Test
    fun bodyStillSupportsLocalClassificationWithoutBeingReturned() {
        val mapped = CloudErrorMapper.map(
            400,
            "context_length_exceeded; user prompt carried local context"
        )

        assertEquals(CloudErrorType.CONTEXT_LENGTH, mapped.type)
        assertTrue(mapped.message.contains("context window"))
        assertFalse(mapped.message.contains("local context"))
    }
}
