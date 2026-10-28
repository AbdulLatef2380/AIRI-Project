package com.airi.assistant.ui.text

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.platform.app.InstrumentationRegistry
import com.airi.assistant.R
import com.airi.assistant.ui.theme.AiriTheme
import org.junit.Rule
import org.junit.Test
import java.util.Locale

class ContentAwareMessageAccessibilityTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun codeActionsExposeLocalizedClickableSemanticsInArabicRtl() {
        val baseContext = InstrumentationRegistry.getInstrumentation().targetContext
        val arabicConfiguration = android.content.res.Configuration(baseContext.resources.configuration).apply {
            setLocale(Locale.forLanguageTag("ar"))
        }
        val arabicContext = baseContext.createConfigurationContext(arabicConfiguration)
        val labels = listOf(
            arabicContext.getString(R.string.copy_code),
            arabicContext.getString(R.string.artifact_expand),
            arabicContext.getString(R.string.artifact_share),
            arabicContext.getString(R.string.artifact_save),
        )

        composeRule.setContent {
            CompositionLocalProvider(
                LocalContext provides arabicContext,
                LocalLayoutDirection provides LayoutDirection.Rtl,
            ) {
                AiriTheme {
                    ContentAwareMessageRenderer(
                        text = "```kotlin\nval accessible = true\n```",
                        messageId = "a11y-arabic",
                        onContentAction = { _, _ -> },
                    )
                }
            }
        }

        labels.forEach { label ->
            composeRule.onNodeWithContentDescription(label)
                .assertIsDisplayed()
                .assertHasClickAction()
        }
    }
}
