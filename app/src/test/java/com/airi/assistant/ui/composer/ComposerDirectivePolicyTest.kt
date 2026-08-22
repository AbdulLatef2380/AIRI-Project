package com.airi.assistant.ui.composer

import org.junit.Assert.assertEquals
import org.junit.Test

class ComposerDirectivePolicyTest {

    @Test
    fun replacesSkillQueryAndPreservesTaskText() {
        assertEquals(
            "/skill:web_search research Android offline models",
            ComposerDirectivePolicy.applySelection("/web research Android offline models", "web_search", isKnowledge = false)
        )
    }

    @Test
    fun replacesKnowledgeQueryAndPreservesArabicTaskText() {
        assertEquals(
            "@knowledge:project_docs لخص مواصفات الواجهة",
            ComposerDirectivePolicy.applySelection("  @docs لخص مواصفات الواجهة", "project_docs", isKnowledge = true)
        )
    }

    @Test
    fun selectionEndsWithSpaceWhenThereIsNoTaskText() {
        assertEquals(
            "/skill:code_assistant ",
            ComposerDirectivePolicy.applySelection("/code", "code_assistant", isKnowledge = false)
        )
    }
}
