package com.airi.assistant.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CodingTaskDetectorTest {
    @Test
    fun literalRequestFromTheFieldReportMatchesWithItsLanguage() {
        val match = CodingTaskDetector.detect("قم بإنشاء كود لآلة حاسبة كاملة بلغة python مع ثيمات متعددة")
        assertNotNull(match)
        assertEquals(listOf("python"), match!!.languages)
        assertEquals("كود", match.target)
    }

    @Test
    fun arabicClitics_andHamzaFormsMatch() {
        listOf(
            "أنشئ كود لآلة حاسبة", "انشئ لي برنامجا بسيطا بلغة جافا", "اكتب لي سكربت بايثون",
            "طوّر تطبيق ملاحظات بلغة kotlin", "برمجة تطبيق للمهام بلغة dart",
        ).forEach { assertTrue(it, CodingTaskDetector.isCodingTask(it)) }
    }

    @Test
    fun englishBuildRequestsMatch() {
        listOf(
            "write a python script that renames files", "create a simple python script",
            "build me a small application using python", "make an app in kotlin",
        ).forEach { assertTrue(it, CodingTaskDetector.isCodingTask(it)) }
    }

    @Test
    fun questionsAndExplanationRequestsAreNotBuildOrders() {
        listOf(
            "كيف اكتب كود بلغة python", "ما هو الفرق بين كود وبرنامج", "اشرح لي هذا الكود",
            "how do I write a python script", "what is a python script", "explain what a program is",
        ).forEach { assertFalse(it, CodingTaskDetector.isCodingTask(it)) }
    }

    @Test
    fun aLanguageNameAloneIsNotTheImplementationLanguage() {
        assertNull(CodingTaskDetector.detect("اكتب لي عن بايثون"))
        assertNull(CodingTaskDetector.detect("write about java"))
        assertNotNull(CodingTaskDetector.detect("اكتب لي شيئا بلغة java"))
    }

    @Test
    fun theCodingObjectMustFollowTheVerbCloselyAndInOrder() {
        assertNull(CodingTaskDetector.detect("my app build failed"))
        assertNull(CodingTaskDetector.detect("write a poem about my app"))
        assertNull(CodingTaskDetector.detect("ابني يكتب برنامج"))
    }

    @Test
    fun ordinaryChatIsUntouched() {
        listOf("مرحبا", "hello", "كم الساعة", "افتح الإعدادات", "اكتب ملف", "ما رأيك في تطبيق جديد")
            .forEach { assertFalse(it, CodingTaskDetector.isCodingTask(it)) }
    }
}
