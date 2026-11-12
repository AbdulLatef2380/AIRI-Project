package com.airi.assistant.connector.oauth

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OAuthCallbackUriPolicyTest {
    @Test
    fun acceptsOnlyExactCallbackPath() {
        assertTrue(OAuthCallbackUriPolicy.accepts("airi", "oauth", "/callback", null))
        assertFalse(OAuthCallbackUriPolicy.accepts("airi", "oauth", "/callback/extra", null))
        assertFalse(OAuthCallbackUriPolicy.accepts("airi", "oauth", "/callback/", null))
    }

    @Test
    fun rejectsWrongSchemeHostAndFragments() {
        assertFalse(OAuthCallbackUriPolicy.accepts("http", "oauth", "/callback", null))
        assertFalse(OAuthCallbackUriPolicy.accepts("airi", "evil", "/callback", null))
        assertFalse(OAuthCallbackUriPolicy.accepts("airi", "oauth", "/callback", "code"))
    }
}
