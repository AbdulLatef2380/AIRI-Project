package com.airi.assistant.connector.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GoogleConnectorActionPolicyTest {

    @Test
    fun readActionsRemainAvailable() {
        assertNull(GoogleConnectorActionPolicy.blockedWriteAction("gmail_list"))
        assertNull(GoogleConnectorActionPolicy.blockedWriteAction("gmail_read"))
        assertNull(GoogleConnectorActionPolicy.blockedWriteAction("calendar_list"))
        assertNull(GoogleConnectorActionPolicy.blockedWriteAction("drive_search"))
    }

    @Test
    fun gmailSendRequiresDurableApproval() {
        assertEquals(
            "Google gmail_send requires an explicit, durable approval flow and is not available yet.",
            GoogleConnectorActionPolicy.blockedWriteAction("gmail_send")
        )
    }

    @Test
    fun calendarCreateRequiresDurableApproval() {
        assertEquals(
            "Google calendar_create requires an explicit, durable approval flow and is not available yet.",
            GoogleConnectorActionPolicy.blockedWriteAction("calendar_create")
        )
    }
}
