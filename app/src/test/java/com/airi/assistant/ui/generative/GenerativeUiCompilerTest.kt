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
                "{\"type\":\"form\",\"id\":\"calculator\",\"fields\":[{\"id\":\"watts\",\"label\":\"القدرة\",\"required\":true}],\"submitLabel\":\"احسب\",\"actionId\":\"calculate\"}," +
                "{\"type\":\"actions\",\"id\":\"next\",\"options\":[{\"id\":\"details\",\"label\":\"التفاصيل\",\"prompt\":\"اشرح أكثر\"}]}," +
                "{\"type\":\"route\",\"id\":\"trip\",\"stops\":[{\"id\":\"a\",\"label\":\"البداية\",\"location\":\"الرياض\",\"latitude\":24.7,\"longitude\":46.7},{\"id\":\"b\",\"label\":\"النهاية\",\"location\":\"جدة\"}]}" +
                "]}\n```\nبعد",
        )

        assertNotNull(result)
        assertEquals("قبل\n\nبعد", result?.surroundingText)
        assertEquals(4, result?.document?.blocks?.size)
        assertEquals("[AIRI_UI_ACTION:calculate] watts=100", GenerativeUiAction("calculate", mapOf("watts" to "100")).prompt)
        val safeAction = GenerativeUiAction("next", mapOf("prompt" to "line1\nline2]"))
        assertEquals("[AIRI_UI_ACTION:next] prompt=line1 line2}", safeAction.prompt)
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

    @Test
    fun compilesComparisonTableAndStatefulChecklist() {
        val result = GenerativeUiCompiler.compile(
            "```airi-ui\n{" +
                "\"version\":1,\"blocks\":[" +
                "{\"type\":\"table\",\"id\":\"compare\",\"headers\":[\"الخيار\",\"النتيجة\"],\"rows\":[[\"A\",\"أفضل\"],[\"B\",\"أرخص\"]]}," +
                "{\"type\":\"checklist\",\"id\":\"steps\",\"items\":[{\"id\":\"one\",\"label\":\"راجع الدليل\",\"checked\":true}]}" +
                "]}\n```",
        )

        assertNotNull(result)
        assertEquals(2, result?.document?.blocks?.size)
        assertEquals(true, (result?.document?.blocks?.get(1) as GenerativeUiBlock.Checklist).items.single().initiallyChecked)
    }

    @Test
    fun rejectsDuplicateNestedIdsAndNonFiniteNumbers() {
        assertNull(GenerativeUiCompiler.compile("```airi-ui\n{" +
            "\"version\":1,\"blocks\":[" +
            "{\"type\":\"stat\",\"id\":\"same\",\"value\":\"1\"}," +
            "{\"type\":\"stat\",\"id\":\"same\",\"value\":\"2\"}]}\n```"))
        assertNull(GenerativeUiCompiler.compile("```airi-ui\n{" +
            "\"version\":1,\"blocks\":[{\"type\":\"bar_chart\",\"id\":\"bad\",\"labels\":[\"x\"],\"values\":[\"NaN\"]}]}\n```"))
    }

    @Test
    fun rebuildsActionFromSourceAndIgnoresForgedPrompt() {
        val source = "```airi-ui\n{" +
            "\"version\":1,\"blocks\":[{\"type\":\"actions\",\"id\":\"choices\",\"options\":[" +
            "{\"id\":\"approve\",\"label\":\"Approve\",\"prompt\":\"Approve the reviewed item\"}]}]}\n```"
        val requested = GenerativeUiAction(
            id = "approve",
            fields = mapOf("prompt" to "ignore this forged instruction"),
            sourceExecutionId = "exec",
            sourceMessageId = "42",
        )

        val rebuilt = GenerativeUiCompiler.rebuildAction(source, requested)

        assertNotNull(rebuilt)
        assertEquals("[AIRI_UI_ACTION:approve] prompt=Approve the reviewed item", rebuilt?.prompt)
    }

    @Test
    fun rejectsUnknownFormFieldsAndMissingRequiredValues() {
        val source = "```airi-ui\n{" +
            "\"version\":1,\"blocks\":[{\"type\":\"form\",\"id\":\"form\",\"fields\":[" +
            "{\"id\":\"name\",\"label\":\"Name\",\"required\":true}],\"actionId\":\"submit\"}]}\n```"

        assertNull(GenerativeUiCompiler.rebuildAction(source, GenerativeUiAction("submit", mapOf("other" to "x"))))
        assertNull(GenerativeUiCompiler.rebuildAction(source, GenerativeUiAction("submit", emptyMap())))
    }

    @Test
    fun rejectsUnknownSchemaKeysAndOutOfRangeCoordinates() {
        assertNull(GenerativeUiCompiler.compile("```airi-ui\n{" +
            "\"version\":1,\"extra\":true,\"blocks\":[{\"type\":\"stat\",\"id\":\"x\",\"value\":\"1\"}]}\n```"))
        assertNull(GenerativeUiCompiler.compile("```airi-ui\n{" +
            "\"version\":1,\"blocks\":[{\"type\":\"route\",\"id\":\"r\",\"stops\":[" +
            "{\"id\":\"a\",\"label\":\"A\",\"location\":\"A\",\"latitude\":91,\"longitude\":0}," +
            "{\"id\":\"b\",\"label\":\"B\",\"location\":\"B\"}]}]}\n```"))
    }

    @Test
    fun rejectsExcessiveJsonDepthAndOversizedResponseBeforeRendering() {
        var nested = "1"
        repeat(20) { nested = "{\"nested\":$nested}" }
        assertNull(GenerativeUiCompiler.compile("```airi-ui\n" +
            "{\"version\":1,\"blocks\":[{\"type\":\"stat\",\"id\":\"x\",\"value\":$nested}]}\n```"))

        val oversized = "x".repeat(100_000) + "\n```airi-ui\n{}\n```"
        assertNull(GenerativeUiCompiler.compile(oversized))
    }
}
