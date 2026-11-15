package com.airi.assistant.ui.generative

import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.airi.assistant.R
import com.airi.assistant.ui.theme.AiriTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GenerativeUiRendererTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val source = "```airi-ui\n{" +
        "\"version\":1,\"title\":\"Review\",\"blocks\":[" +
        "{\"type\":\"actions\",\"id\":\"choices\",\"title\":\"Next step\",\"options\":[" +
        "{\"id\":\"approve\",\"label\":\"Approve\",\"prompt\":\"Approve the reviewed item\"}]}]}\n```"

    @Test
    fun actionPreviewRequiresExplicitConfirmationAndDoesNotSend() {
        var received: GenerativeUiAction? = null
        val compiled = requireNotNull(GenerativeUiCompiler.compile(source))
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        composeRule.setContent {
            AiriTheme {
                GenerativeUiRenderer(
                    compiled = compiled,
                    sourceExecutionId = "execution-1",
                    sourceMessageId = "message-1",
                    onAction = { received = it },
                )
            }
        }

        composeRule.onNodeWithText("Approve").assertIsDisplayed().performClick()
        composeRule.onNodeWithText(context.getString(R.string.generative_ui_action_preview_title)).assertIsDisplayed()
        composeRule.onNodeWithText("Approve the reviewed item").assertIsDisplayed()
        assertNull(received)

        composeRule.onNodeWithText(context.getString(R.string.generative_ui_action_preview_use)).performClick()
        composeRule.waitForIdle()
        assertNotNull(received)
        assertEquals("approve", received?.id)
        assertEquals("execution-1", received?.sourceExecutionId)
        assertEquals("message-1", received?.sourceMessageId)
        assertEquals("[AIRI_UI_ACTION:approve] prompt=Approve the reviewed item", received?.prompt)
    }

    @Test
    fun cancellingPreviewLeavesActionAvailableAndReopeningIsSafe() {
        var received: GenerativeUiAction? = null
        val compiled = requireNotNull(GenerativeUiCompiler.compile(source))
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        composeRule.setContent {
            AiriTheme {
                GenerativeUiRenderer(compiled = compiled, onAction = { received = it })
            }
        }

        composeRule.onNodeWithText("Approve").performClick()
        composeRule.onNodeWithText(context.getString(R.string.generative_ui_action_preview_title)).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.generative_ui_action_preview_cancel)).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText(context.getString(R.string.generative_ui_action_preview_title)).assertDoesNotExist()
        assertNull(received)

        composeRule.onNodeWithText("Approve").performClick()
        composeRule.onNodeWithText(context.getString(R.string.generative_ui_action_preview_title)).assertIsDisplayed()
        assertNull(received)
    }

    @Test
    fun requiredFormFeedbackUsesAnimatedVisibilityBeforePreview() {
        val formSource = "```airi-ui\n{" +
            "\"version\":1,\"blocks\":[{\"type\":\"form\",\"id\":\"review\",\"fields\":[" +
            "{\"id\":\"name\",\"label\":\"Name\",\"required\":true}]," +
            "\"submitLabel\":\"Review\",\"actionId\":\"review\"}]}\n```"
        val compiled = requireNotNull(GenerativeUiCompiler.compile(formSource))

        composeRule.setContent {
            AiriTheme { GenerativeUiRenderer(compiled = compiled) }
        }

        composeRule.onNodeWithText("Review").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Complete the required fields before submitting.").assertIsDisplayed()
    }
}
