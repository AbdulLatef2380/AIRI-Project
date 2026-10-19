package com.airi.assistant.connector.app.github

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GithubSessionStoreTest {
    @Test
    fun sessionsAndRepositoryGrantsAreIsolatedByProject() {
        val backing = FakeCredentialStore()
        val store = GithubSessionStore(backing)
        val first = session("project-one", "alpha")
        val second = session("project-two", "beta")

        assertEquals(GithubSessionWriteResult.Saved, store.save(first))
        assertEquals(GithubSessionWriteResult.Saved, store.save(second))

        val loadedFirst = (store.load("project-one") as GithubSessionReadResult.Found).session
        val loadedSecond = (store.load("project-two") as GithubSessionReadResult.Found).session
        assertEquals("alpha", loadedFirst.accountLogin)
        assertEquals("owner/alpha", loadedFirst.repositories.single().fullName)
        assertEquals("beta", loadedSecond.accountLogin)
        assertEquals("owner/beta", loadedSecond.repositories.single().fullName)
        assertNotEquals(store.namespaceFor("project-one"), store.namespaceFor("project-two"))
        assertFalse(store.namespaceFor("project-one").contains("project-one"))
    }

    @Test
    fun projectMismatchAndInvalidProjectFailClosed() {
        val store = GithubSessionStore(FakeCredentialStore())
        assertTrue(store.load("../other") is GithubSessionReadResult.Failure)
        assertTrue(store.save(session("../other", "alpha")) is GithubSessionWriteResult.Failure)
    }

    @Test
    fun unavailableSecureStorageDoesNotPretendToSaveOrRead() {
        val store = GithubSessionStore(FakeCredentialStore(available = false))
        val write = store.save(session("project-one", "alpha")) as GithubSessionWriteResult.Failure
        val read = store.load("project-one") as GithubSessionReadResult.Failure
        assertEquals(GithubAuthorizationFailureCode.SECURE_STORAGE_UNAVAILABLE, write.failure.code)
        assertEquals(GithubAuthorizationFailureCode.SECURE_STORAGE_UNAVAILABLE, read.failure.code)
    }

    @Test
    fun sessionAndBrokerRequestDiagnosticsRedactCredentialsAndVerifier() {
        val value = session("project-one", "alpha")
        val sessionText = value.toString()
        assertFalse(sessionText.contains("access-secret-fixture"))
        assertFalse(sessionText.contains("refresh-secret-fixture"))
        assertTrue(sessionText.contains("<redacted>"))

        val requestText = GithubBrokerCodeExchange(
            projectId = "project-one",
            authorizationCode = "authorization-code-fixture",
            codeVerifier = "verifier-fixture",
            requestedScopes = setOf("repo:read"),
        ).toString()
        assertFalse(requestText.contains("authorization-code-fixture"))
        assertFalse(requestText.contains("verifier-fixture"))
    }

    private fun session(projectId: String, login: String) = GithubAuthorizationSession(
        projectId = projectId,
        accountLogin = login,
        accessToken = "access-secret-fixture",
        refreshToken = "refresh-secret-fixture",
        expiresAtMs = 50_000L,
        grantedScopes = setOf("repo:read"),
        installationId = 456L,
        repositories = listOf(
            GithubRepositoryGrant(
                repositoryId = if (login == "alpha") 1L else 2L,
                owner = "owner",
                name = login,
                permissions = mapOf("contents" to GithubRepositoryPermission.READ),
            )
        ),
        createdAtMs = 1_000L,
    )

    private class FakeCredentialStore(override val isAvailable: Boolean = true) : GithubSessionCredentialStore {
        private val values = mutableMapOf<String, String>()
        override fun read(namespace: String): String? = if (isAvailable) values[namespace] else null
        override fun write(namespace: String, value: String): Boolean {
            if (!isAvailable) return false
            values[namespace] = value
            return true
        }
        override fun remove(namespace: String): Boolean = isAvailable && values.remove(namespace) != null
    }
}
