package com.airi.assistant.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LanguagePolicyTest {
    @Test fun arabicInputIsDetectedAndRequiresArabicResponse() {
        val input = "هل يمكنك مساعدتي؟"
        assertEquals(UserLanguage.ARABIC, LanguagePolicy.detect(input))
        assertTrue(LanguagePolicy.responseInstruction(input).contains("entirely in Arabic"))
    }

    @Test fun englishInputIsDetectedAndRequiresEnglishResponse() {
        val input = "Can you help me?"
        assertEquals(UserLanguage.ENGLISH, LanguagePolicy.detect(input))
        assertTrue(LanguagePolicy.responseInstruction(input).contains("entirely in English"))
    }

    @Test fun mixedInputDoesNotPretendTheLanguageIsCloudModelChoice() {
        assertEquals(UserLanguage.MIXED, LanguagePolicy.detect("مرحبا AIRI"))
    }
}
