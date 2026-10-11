package com.airi.assistant.ui.viewmodel

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectorScreenIsolationPolicyTest {
    @Test
    fun acceptsOnlyResultForTheActiveConnector() {
        assertTrue(ConnectorScreenIsolationPolicy.acceptsResult("github", "github"))
        assertFalse(ConnectorScreenIsolationPolicy.acceptsResult("github", "telegram"))
        assertFalse(ConnectorScreenIsolationPolicy.acceptsResult("github", null))
    }

    @Test
    fun rejectsBlankConnectorIds() {
        assertFalse(ConnectorScreenIsolationPolicy.acceptsResult("", "github"))
        assertFalse(ConnectorScreenIsolationPolicy.acceptsResult("github", ""))
        assertFalse(ConnectorScreenIsolationPolicy.acceptsDialogUpdate("", ""))
    }

    @Test
    fun dialogUpdatesUseTheSameIsolationRule() {
        assertTrue(ConnectorScreenIsolationPolicy.acceptsDialogUpdate("notion", "notion"))
        assertFalse(ConnectorScreenIsolationPolicy.acceptsDialogUpdate("notion", "github"))
    }
}
