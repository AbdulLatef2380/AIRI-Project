package com.airi.assistant.ai

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.airi.assistant.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChatAcceptanceInstrumentedTest {
    @Test fun arabicAcceptanceContractUsesArabicAndLocalizedGeneratingLabel() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals(UserLanguage.ARABIC, LanguagePolicy.detect("هل يمكنك مساعدتي"))
        assertTrue(LanguagePolicy.responseInstruction("هل يمكنك مساعدتي").contains("Arabic"))
        assertTrue(context.getString(R.string.generating).isNotBlank())
    }

    @Test fun englishAcceptanceContractUsesEnglish() {
        assertEquals(UserLanguage.ENGLISH, LanguagePolicy.detect("Can you help me"))
    }
}
