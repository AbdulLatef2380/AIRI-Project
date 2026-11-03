package com.airi.assistant.accessibility.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** V1: confirmation is decided from the UI element label, with whole-word matching. */
class ConfirmationMatchingTest {

    @Test
    fun consequentialLabelsNeedConfirmation() {
        listOf(
            "Pay now", "Buy", "Purchase", "Transfer", "Submit", "Confirm", "Publish",
            "Reply", "Unsubscribe", "Clear all", "Erase", "Uninstall", "Reset", "Format",
            "Sending message", "Delete chat", "Remove"
        ).forEach { label ->
            assertTrue("'$label' must need confirmation", AccessibilityActionGateway.labelRequiresConfirmation(label))
        }
    }

    @Test
    fun arabicLabelsNeedConfirmation() {
        listOf("ادفع الآن", "أرسل", "احذف", "تأكيد").forEach { label ->
            assertTrue("'$label' must need confirmation", AccessibilityActionGateway.labelRequiresConfirmation(label))
        }
    }

    @Test
    fun substringsInsideOtherWordsDoNotTrigger() {
        listOf("prototype", "Sender name", "Compost", "Postpone", "Forward").forEach { label ->
            assertFalse("'$label' must NOT need confirmation", AccessibilityActionGateway.labelRequiresConfirmation(label))
        }
    }

    @Test
    fun harmlessLabelsAreAllowed() {
        listOf("Wi-Fi", "Next", "Back", "Search", "", "حول").forEach { label ->
            assertFalse("'$label' must be allowed", AccessibilityActionGateway.labelRequiresConfirmation(label))
        }
    }
}
