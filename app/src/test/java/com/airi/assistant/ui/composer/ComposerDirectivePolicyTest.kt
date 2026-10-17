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

    @Test
    fun selectedSkillBackspaceOpensPickerArmsThenRemoves() {
        assertEquals(ComposerDirectivePolicy.SkillBackspaceAction.OPEN_PICKER, ComposerDirectivePolicy.nextSkillBackspaceAction(0))
        assertEquals(ComposerDirectivePolicy.SkillBackspaceAction.ARM_REMOVAL, ComposerDirectivePolicy.nextSkillBackspaceAction(1))
        assertEquals(ComposerDirectivePolicy.SkillBackspaceAction.REMOVE_SKILL, ComposerDirectivePolicy.nextSkillBackspaceAction(2))
    }

    @Test
    fun selectedSkillsCanBeAppendedWithoutLosingTaskText() {
        assertEquals(
            "/skill:web_search /skill:code_assistant إصلاح المشروع",
            ComposerDirectivePolicy.applySelection(
                "/skill:web_search / إصلاح المشروع",
                "code_assistant",
                isKnowledge = false,
            )
        )
    }

    @Test
    fun selectedSkillsCanBeRemovedIndividually() {
        val text = "/skill:web_search /skill:code_assistant إصلاح المشروع"
        assertEquals(listOf("web_search", "code_assistant"), ComposerDirectivePolicy.leadingSkillIds(text))
        assertEquals(
            "/skill:web_search إصلاح المشروع",
            ComposerDirectivePolicy.removeSkill(text, "code_assistant")
        )
        assertEquals(
            "إصلاح المشروع",
            ComposerDirectivePolicy.taskText(ComposerDirectivePolicy.removeSkill(text, "web_search"))
        )
    }

    @Test
    fun queryReplacementDoesNotConsumeSlashInsideTaskText() {
        assertEquals(
            "/skill:web_search إصلاح /tmp/file",
            ComposerDirectivePolicy.applySelection("/web إصلاح /tmp/file", "web_search", isKnowledge = false)
        )
    }

    @Test
    fun queryReplacementHandlesLeadingWhitespaceAndKnowledgeQueries() {
        assertEquals(
            "@knowledge:project_docs لخص @docs/architecture",
            ComposerDirectivePolicy.applySelection("  @pro لخص @docs/architecture", "project_docs", isKnowledge = true)
        )
    }
}
