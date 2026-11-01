package com.airi.assistant.ui.text

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performDoubleClick
import androidx.test.platform.app.InstrumentationRegistry
import com.airi.assistant.R
import com.airi.assistant.ui.theme.AiriTheme
import com.airi.assistant.ui.util.MarkdownText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ContentAwareMessageRendererTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun codeCardDoesNotExposeAnExpandAction() {
        composeRule.setContent {
            AiriTheme {
                ContentAwareMessageRenderer(
                    text = "```kotlin\nval retained = true\n```",
                    messageId = "streaming-message",
                    onContentAction = { _, _ -> },
                )
            }
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertTrue(composeRule.onAllNodesWithContentDescription(context.getString(R.string.artifact_expand)).fetchSemanticsNodes().isEmpty())
        composeRule.onNodeWithText("val retained = true").assertIsDisplayed()
    }

    @Test
    fun codeActionsAreDispatchedWithTheExactUnitTargetAndPolicyAction() {
        var receivedTarget: ContentTarget? = null
        var receivedAction: ContentUnitAction? = null
        composeRule.setContent {
            AiriTheme {
                ContentAwareMessageRenderer(
                    text = "```kotlin\nval exact = 7\n```",
                    messageId = "message-actions",
                    onContentAction = { target, action ->
                        receivedTarget = target
                        receivedAction = action
                    },
                )
            }
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        composeRule.onNodeWithContentDescription(context.getString(R.string.copy_code)).performClick()
        assertEquals(ContentUnitAction.COPY, receivedAction)
        assertEquals("val exact = 7", receivedTarget?.source)

        composeRule.onNodeWithContentDescription(context.getString(R.string.artifact_share)).performClick()
        assertEquals(ContentUnitAction.SHARE, receivedAction)
        composeRule.onNodeWithContentDescription(context.getString(R.string.artifact_save)).performClick()
        assertEquals(ContentUnitAction.SAVE, receivedAction)
        composeRule.onNodeWithText("val exact = 7").performDoubleClick()
        assertEquals(ContentUnitAction.FULLSCREEN, receivedAction)
    }

    @Test
    fun standaloneMarkdownCodeBlockUsesItsCopyCallback() {
        var copied: String? = null
        composeRule.setContent {
            AiriTheme {
                MarkdownText(
                    rawText = "```kotlin\nval standalone = true\n```",
                    onCopyCode = { copied = it },
                )
            }
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        composeRule.onNodeWithContentDescription(context.getString(R.string.copy_code)).performClick()
        assertEquals("val standalone = true", copied)
    }

    @Test
    fun toolOutputIsSeparateAndHiddenUntilTheUserExpandsIt() {
        val toolOutput = AssistantToolOutput(
            id = "tool-message-1",
            toolName = "search",
            step = 1,
            text = "tool-only payload",
        )
        composeRule.setContent {
            AiriTheme {
                ContentAwareMessageRenderer(
                    text = "Agent-visible answer",
                    messageId = "presentation-separation",
                    toolOutputs = listOf(toolOutput),
                    onContentAction = { _, _ -> },
                )
            }
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        composeRule.onNodeWithText("Agent-visible answer").assertIsDisplayed()
        assertTrue(composeRule.onAllNodesWithText("tool-only payload").fetchSemanticsNodes().isEmpty())
        composeRule.onNodeWithContentDescription(context.getString(R.string.assistant_tool_output_show)).performClick()
        composeRule.onNodeWithText("tool-only payload").assertIsDisplayed()
        composeRule.onNodeWithText("Agent-visible answer").assertIsDisplayed()
    }
}
