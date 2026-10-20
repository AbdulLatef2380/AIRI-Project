package com.airi.assistant.connector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GithubCredentialSubmissionPolicyTest {
    @Test
    fun genericCredentialSubmissionRejectsGitHubAndPreservesOtherProviders() {
        val rejected = GithubCredentialSubmissionPolicy.rejection("github")

        assertEquals("github_broker_required", rejected?.code)
        assertTrue(rejected?.message.orEmpty().contains("authorization broker", ignoreCase = true))
        assertNull(GithubCredentialSubmissionPolicy.rejection("telegram"))
        assertNull(GithubCredentialSubmissionPolicy.rejection("gitlab"))
    }
}
