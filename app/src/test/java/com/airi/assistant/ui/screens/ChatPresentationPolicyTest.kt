package com.airi.assistant.ui.screens

import androidx.compose.ui.unit.LayoutDirection
import com.airi.assistant.ui.text.LanguageRuntimeManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatPresentationPolicyTest {
    @Test fun arabicTextUsesRtl() {
        assertEquals(LanguageRuntimeManager.DominantDirection.RTL, LanguageRuntimeManager.analyseDirection("أهلًا بك"))
        assertEquals(LayoutDirection.Rtl, ChatPresentationPolicy.textDirection("أهلًا بك"))
    }

    @Test fun englishTextUsesLtr() {
        assertEquals(LanguageRuntimeManager.DominantDirection.LTR, LanguageRuntimeManager.analyseDirection("Hello AIRI"))
        assertEquals(LayoutDirection.Ltr, ChatPresentationPolicy.textDirection("Hello AIRI"))
    }

    @Test fun mixedTextUsesContentAwareBidiDirection() {
        assertEquals(LanguageRuntimeManager.DominantDirection.MIXED, LanguageRuntimeManager.analyseDirection("شغّل adb shell الآن"))
        assertEquals(LayoutDirection.Ltr, ChatPresentationPolicy.textDirection("https://example.com/a?b=1"))
    }

    @Test fun messageLengthThresholdsAreStable() {
        assertEquals(ChatPresentationPolicy.MessageLength.SHORT, ChatPresentationPolicy.classifyLength("a"))
        assertEquals(ChatPresentationPolicy.MessageLength.MEDIUM, ChatPresentationPolicy.classifyLength("a".repeat(41)))
        assertEquals(ChatPresentationPolicy.MessageLength.LONG, ChatPresentationPolicy.classifyLength("a".repeat(201)))
        assertEquals(ChatPresentationPolicy.MessageLength.VERY_LONG, ChatPresentationPolicy.classifyLength("a".repeat(4001)))
    }

    @Test fun userBubbleNeverExceedsConfiguredFraction() {
        assertTrue(ChatPresentationPolicy.userBubbleFraction("short") in 0f..0.82f)
        assertTrue(ChatPresentationPolicy.userBubbleFraction("long text".repeat(500)) in 0f..0.82f)
    }

    @Test fun rawProviderIdsGetHumanLabels() {
        assertEquals("Gemini Flash", ChatPresentationPolicy.humanModelLabel("gemini-3.8-flash", false, true))
        assertEquals("Local", ChatPresentationPolicy.humanModelLabel("", true, false))
        assertEquals("Auto", ChatPresentationPolicy.humanModelLabel("", false, false))
    }
}
