package com.airi.assistant.ui.screens

import android.content.res.Configuration
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.runtime.CompositionLocalProvider
import com.airi.assistant.ui.theme.AiriTheme
import com.airi.assistant.ui.viewmodel.ChatMessage
import com.airi.assistant.ui.viewmodel.ModelUiState

private val previewMessages = listOf(
    ChatMessage("أهلًا بك", isUser = true),
    ChatMessage("مرحبًا! كيف يمكنني مساعدتك اليوم؟", isUser = false),
    ChatMessage("Can you inspect this code?", isUser = true),
    ChatMessage("```kotlin\nfun greet() = \"AIRI\"\n```", isUser = false),
)

@Preview(name = "Chat empty dark", widthDp = 360, heightDp = 800, uiMode = Configuration.UI_MODE_NIGHT_YES, showBackground = true)
@Composable
private fun ChatEmptyDarkPreview() {
    AiriTheme { ChatMessageList(emptyList(), streamingText = "", isGenerating = false) }
}

@Preview(name = "Chat messages light", widthDp = 411, heightDp = 891, showBackground = true)
@Composable
private fun ChatMessagesLightPreview() {
    AiriTheme { ChatMessageList(previewMessages, streamingText = "", isGenerating = false) }
}

@Preview(name = "Arabic user RTL", widthDp = 360, heightDp = 800, showBackground = true)
@Composable
private fun ArabicUserPreview() {
    AiriTheme {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            UserBubble(text = "أهلًا، أريد مراجعة هذا المشروع")
        }
    }
}

@Preview(name = "English user LTR", widthDp = 411, heightDp = 891, showBackground = true)
@Composable
private fun EnglishUserPreview() {
    AiriTheme {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            UserBubble(text = "Please review this project and summarize the risks")
        }
    }
}

@Preview(name = "Mixed response", widthDp = 600, heightDp = 800, showBackground = true)
@Composable
private fun MixedResponsePreview() {
    AiriTheme {
        AiBubble(text = "شغّل `adb shell pm list packages` ثم افحص https://example.com")
    }
}

@Preview(name = "Long code response", widthDp = 840, heightDp = 900, showBackground = true)
@Composable
private fun LongCodeResponsePreview() {
    AiriTheme {
        AiBubble(
            text = "```kotlin\n" + "fun example() {\n    println(\"AIRI\")\n}\n".repeat(12) + "```"
        )
    }
}

@Preview(name = "Streaming", widthDp = 411, heightDp = 891, showBackground = true)
@Composable
private fun StreamingPreview() {
    AiriTheme { AiStreamingBubble("جارٍ تجهيز الرد…") }
}

@Preview(name = "Composer empty", widthDp = 360, heightDp = 800, showBackground = true)
@Composable
private fun ComposerEmptyPreview() {
    AiriTheme {
        AiriChatInputBar(
            modelState = ModelUiState(isCloudReady = true),
            isGenerating = false,
            voiceInput = "",
            onSend = { _, _ -> },
            onMicClick = {},
            onVoiceChatClick = {},
            onVoiceConsumed = {},
            onOpenModels = {},
        )
    }
}

@Preview(name = "Composer generating", widthDp = 411, heightDp = 891, showBackground = true)
@Composable
private fun ComposerGeneratingPreview() {
    AiriTheme {
        AiriChatInputBar(
            modelState = ModelUiState(isCloudReady = true),
            isGenerating = true,
            voiceInput = "",
            onSend = { _, _ -> },
            onCancel = {},
            onMicClick = {},
            onVoiceChatClick = {},
            onVoiceConsumed = {},
            onOpenModels = {},
        )
    }
}
