package com.airi.assistant.agent.subagent.impl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ResearchEvidencePolicyTest {

    @Test
    fun publicSourceProducesBoundedUntrustedCitationBlock() {
        val evidence = ResearchEvidencePolicy.fromSearchResult(
            provider = "DuckDuckGo",
            summary = "A factual summary.",
            sourceUrl = "https://example.com/research"
        )

        assertNotNull(evidence)
        val block = ResearchEvidencePolicy.formatForSynthesis(evidence!!)
        assertEquals("https://example.com/research", evidence.sourceUrl)
        assertTrue(block.contains("trust=\"untrusted_external\""))
        assertTrue(block.contains("Citation URL: https://example.com/research"))
        assertTrue(block.contains("A factual summary."))
    }

    @Test
    fun privateSourceIsNotRetainedAsCitation() {
        val evidence = ResearchEvidencePolicy.fromSearchResult(
            provider = "DuckDuckGo",
            summary = "Result text remains bounded evidence.",
            sourceUrl = "http://127.0.0.1/private"
        )

        assertNotNull(evidence)
        assertNull(evidence!!.sourceUrl)
        assertFalse(ResearchEvidencePolicy.formatForSynthesis(evidence).contains("127.0.0.1"))
    }

    @Test
    fun blankEvidenceIsRejectedAndLongEvidenceIsBounded() {
        assertNull(ResearchEvidencePolicy.fromSearchResult("search", " ", "https://example.com"))

        val evidence = ResearchEvidencePolicy.fromSearchResult(
            provider = "search",
            summary = "x".repeat(ResearchEvidencePolicy.MAX_EVIDENCE_CHARS + 100),
            sourceUrl = "https://example.com"
        )

        assertNotNull(evidence)
        assertEquals(ResearchEvidencePolicy.MAX_EVIDENCE_CHARS, evidence!!.content.length)
    }
}
