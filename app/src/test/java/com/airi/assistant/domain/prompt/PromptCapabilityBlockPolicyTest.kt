package com.airi.assistant.domain.prompt

import org.junit.Assert.assertEquals
import org.junit.Test

class PromptCapabilityBlockPolicyTest {
    @Test fun structuredToolBlockSuppressesNarrativeDuplicate() {
        assertEquals(
            "",
            PromptCapabilityBlockPolicy.narrativeBlockIfNeeded(
                "base\nAVAILABLE TOOLS:\n- github_get_file",
                "\nYou have access to the following real tools.",
            ),
        )
    }

    @Test fun unrelatedPromptReceivesNarrativeBlockOnce() {
        assertEquals(
            "\nYou have access to the following real tools.",
            PromptCapabilityBlockPolicy.narrativeBlockIfNeeded(
                "base",
                "\nYou have access to the following real tools.",
            ),
        )
    }
}
