package com.airi.assistant.ui.generative

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class GenerativeUiCompilerTest {
    @Test
    fun compilesAllowedChartAndForm() {
        val result = GenerativeUiCompiler.compile(
            "قبل\n```airi-ui\n" +
                "{\"version\":1,\"title\":\"استهلاك\",\"blocks\":[" +
                "{\"type\":\"bar_chart\",\"id\":\"monthly\",\"labels\":[\"يناير\",\"فبراير\"],\"values\":[10,20],\"unit\":\" kWh\"}," +
                "{\"type\":\"form\",\"id\":\"calculator\",\"fields\":[{\"id\":\"watts\",\"label\":\"القدرة\",\"required\":true}],\"submitLabel\":\"احسب\",\"actionId\":\"calculate\"}" +
                "]}\n```\nبعد",
        )

        assertNotNull(result)
        assertEquals("قبل\n\nبعد", result?.surroundingText)
        assertEquals(2, result?.document?.blocks?.size)
        assertEquals("[AIRI_UI_ACTION:calculate] watts=100", GenerativeUiAction("calculate", mapOf("watts" to "100")).prompt)
    }

    @Test
    fun rejectsUnknownBlockAndExecutablePayload() {
        assertNull(GenerativeUiCompiler.compile("```airi-ui\n{\"version\":1,\"blocks\":[{\"type\":\"html\",\"id\":\"x\",\"value\":\"<script>bad</script>\"}]}\n```"))
        assertNull(GenerativeUiCompiler.compile("```airi-ui\n{\"version\":2,\"blocks\":[{\"type\":\"stat\",\"id\":\"x\",\"value\":\"1\"}]}\n```"))
    }

    @Test
    fun ignoresPlainTextWithoutUiFence() {
        assertNull(GenerativeUiCompiler.compile("إجابة نصية عادية بلا واجهة."))
    }
}
