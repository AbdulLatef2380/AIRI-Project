package com.airi.assistant.execution.security

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.airi.assistant.execution.CloudProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SecureApiKeyStoreInstrumentedTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val endpointOne = "https://secure-test-one.invalid/v1"
    private val endpointTwo = "https://secure-test-two.invalid/v1"

    @After
    fun tearDown() {
        SecureApiKeyStore(context).apply {
            clearKey(CloudProvider.OPENAI)
            clearCustomEndpointKey(endpointOne)
            clearCustomEndpointKey(endpointTwo)
        }
    }

    @Test
    fun providerKeyCanBeSavedOverwrittenRestoredAndCleared() {
        val firstStore = SecureApiKeyStore(context)
        firstStore.saveKey(CloudProvider.OPENAI, "  test-key-one  ")
        assertEquals("test-key-one", firstStore.getKey(CloudProvider.OPENAI))

        val restoredStore = SecureApiKeyStore(context)
        assertEquals("test-key-one", restoredStore.getKey(CloudProvider.OPENAI))

        restoredStore.saveKey(CloudProvider.OPENAI, "test-key-two")
        assertEquals("test-key-two", SecureApiKeyStore(context).getKey(CloudProvider.OPENAI))

        restoredStore.saveKey(CloudProvider.OPENAI, "   ")
        assertNull(SecureApiKeyStore(context).getKey(CloudProvider.OPENAI))
        assertFalse(SecureApiKeyStore(context).hasKey(CloudProvider.OPENAI))
    }

    @Test
    fun customEndpointKeysRemainIsolatedAndCanBeRemovedIndependently() {
        val store = SecureApiKeyStore(context)
        store.saveCustomEndpointKey(endpointOne, "endpoint-one-key")
        store.saveCustomEndpointKey(endpointTwo, "endpoint-two-key")

        assertEquals("endpoint-one-key", store.getCustomEndpointKey(endpointOne))
        assertEquals("endpoint-two-key", store.getCustomEndpointKey(endpointTwo))

        store.clearCustomEndpointKey(endpointOne)
        assertNull(SecureApiKeyStore(context).getCustomEndpointKey(endpointOne))
        assertEquals("endpoint-two-key", SecureApiKeyStore(context).getCustomEndpointKey(endpointTwo))
    }
}
