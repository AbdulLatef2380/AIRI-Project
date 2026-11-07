package com.airi.assistant.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptDraftComposerTest {
    private val templates = PromptDraftTemplates(
        focusedFormat = "Focused goal={{GOAL}} audience={{AUDIENCE}} constraints={{CONSTRAINTS}} model={{MODEL_PREFERENCE}} tone={{TONE}} skills={{SKILLS}} connectors={{CONNECTORS}}",
        agentWorkflowFormat = "Agent workflow goal={{GOAL}} audience={{AUDIENCE}} constraints={{CONSTRAINTS}} model={{MODEL_PREFERENCE}} tone={{TONE}} skills={{SKILLS}} connectors={{CONNECTORS}}; request approval before external effects",
        unspecifiedAudience = "unspecified audience",
        unspecifiedConstraints = "no extra constraints",
        noneSelected = "none selected",
    )

    @Test
    fun generatedVariantsIncludeAllCurrentUserSelections() {
        val drafts = PromptDraftComposer.generate(
            PromptDraftInput(
                goal = "  Audit this repository  ",
                audience = "Android maintainers",
                constraints = "Do not modify unrelated files",
                modelPreference = "Reasoning preferred",
                tone = "Professional",
                skills = listOf("Code review", "Testing", "Code review"),
                connectors = listOf("GitHub"),
            ),
            templates,
        )

        assertEquals(listOf(PromptDraftKind.FOCUSED, PromptDraftKind.AGENT_WORKFLOW), drafts.map { it.kind })
        drafts.forEach { draft ->
            assertTrue(draft.body, draft.body.contains("Audit this repository"))
            assertTrue(draft.body, draft.body.contains("Android maintainers"))
            assertTrue(draft.body, draft.body.contains("Do not modify unrelated files"))
            assertTrue(draft.body, draft.body.contains("Reasoning preferred"))
            assertTrue(draft.body, draft.body.contains("Professional"))
            assertTrue(draft.body, draft.body.contains("Code review, Testing"))
            assertTrue(draft.body, draft.body.contains("GitHub"))
        }
        assertFalse("Candidate prompts should be distinct", drafts[0].body == drafts[1].body)
    }

    @Test
    fun userTextThatLooksLikeTemplateTokensIsPreservedLiterally() {
        val goal = "Keep the literal {{AUDIENCE}} and {{TONE}} markers"
        val drafts = PromptDraftComposer.generate(PromptDraftInput(goal = goal), templates)

        drafts.forEach { draft -> assertTrue(draft.body, draft.body.contains(goal)) }
    }

    @Test
    fun blankGoalProducesNoDraftAndEmptySelectionsUseExplicitFallbacks() {
        assertTrue(PromptDraftComposer.generate(PromptDraftInput(goal = " \n "), templates).isEmpty())

        val drafts = PromptDraftComposer.generate(
            PromptDraftInput(goal = "Summarize a document"),
            templates,
        )
        assertEquals(2, drafts.size)
        drafts.forEach { draft ->
            assertTrue(draft.body, draft.body.contains("unspecified audience"))
            assertTrue(draft.body, draft.body.contains("no extra constraints"))
            assertTrue(draft.body, draft.body.contains("none selected"))
        }
    }
}
