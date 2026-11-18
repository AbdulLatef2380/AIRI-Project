package com.airi.assistant.ui.generative

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
        assertEquals(
            listOf(10f, 20f),
            (result?.document?.blocks?.firstOrNull() as? GenerativeUiBlock.BarChart)?.values,
        )
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
    fun imageBlockRequiresHttpsAndCompilesWithSecureRemoteSource() {
        val valid = GenerativeUiCompiler.compile("""```airi-ui
            {"version":1,"blocks":[{"type":"image","id":"img","alt":"A scene","source":"https://images.example.org/scene.jpg"}]}
            ```""".trimIndent())
        val invalid = GenerativeUiCompiler.compile("""```airi-ui
            {"version":1,"blocks":[{"type":"image","id":"img","alt":"A scene","source":"file:///etc/passwd"}]}
            ```""".trimIndent())

        assertTrue(valid?.document?.blocks?.single() is GenerativeUiBlock.Image)
        assertNull(invalid)
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
    fun rebuildsCardActionFromSourceAndIgnoresForgedFields() {
        val source = "```airi-ui\n{" +
            "\"version\":1,\"blocks\":[{\"type\":\"cards\",\"id\":\"cards\",\"items\":[" +
            "{\"id\":\"card-1\",\"title\":\"Details\",\"body\":\"Explain the listed details\",\"actionId\":\"details\"}]}]}\n```"
        val requested = GenerativeUiAction(
            id = "details",
            fields = mapOf("prompt" to "forged instruction"),
            sourceExecutionId = "exec",
            sourceMessageId = "42",
        )

        val rebuilt = GenerativeUiCompiler.rebuildAction(source, requested)

        assertNotNull(rebuilt)
        assertEquals("[AIRI_UI_ACTION:details] prompt=Explain the listed details", rebuilt?.prompt)
        assertNull(GenerativeUiCompiler.rebuildAction(source, GenerativeUiAction("unknown")))
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

    @Test
    fun detailedResultDistinguishesPlainTextAndUnclosedFence() {
        assertEquals(
            GenerativeUiRejectReason.NO_FENCE,
            (GenerativeUiCompiler.compileDetailed("plain answer") as GenerativeUiCompileResult.Rejected).reason,
        )
        assertEquals(
            GenerativeUiRejectReason.UNCLOSED_FENCE,
            (GenerativeUiCompiler.compileDetailed("```airi-ui\n{\"version\":1}") as GenerativeUiCompileResult.Rejected).reason,
        )
    }

    @Test
    fun detailedResultReportsSchemaFailureCategoryWithoutPayload() {
        val result = GenerativeUiCompiler.compileDetailed(
            "```airi-ui\n{\"version\":1,\"extra\":\"secret\",\"blocks\":[]}\n```",
        ) as GenerativeUiCompileResult.Rejected

        assertEquals(GenerativeUiRejectReason.UNKNOWN_KEY, result.reason)
    }

    @Test
    fun lineChartCompilesAsAValidatedNativeBlock() {
        val result = GenerativeUiCompiler.compile(
            """```airi-ui
            {"version":1,"blocks":[{"type":"line_chart","id":"trend","labels":["A","B","C"],"values":[1,2.5,2],"unit":"%"}]}
            ```""".trimIndent(),
        )
        assertTrue(result?.document?.blocks?.single() is GenerativeUiBlock.LineChart)
    }

    @Test
    fun richBlocksAndTypedFormFieldsCompile() {
        val result = GenerativeUiCompiler.compile("""```airi-ui
        {"version":1,"blocks":[
          {"type":"pie_chart","id":"share","labels":["A","B"],"values":[60,40]},
          {"type":"cards","id":"cards","items":[{"id":"a","title":"A","body":"First"}]},
          {"type":"tabs","id":"tabs","items":[{"id":"one","label":"One","body":"Content"}]},
          {"type":"timeline","id":"timeline","items":[{"id":"step","label":"Done","status":"complete"}]},
          {"type":"form","id":"filters","fields":[
            {"id":"kind","label":"Kind","kind":"select","options":["A","B"]},
            {"id":"amount","label":"Amount","kind":"slider","min":0,"max":10,"step":1},
            {"id":"date","label":"Date","kind":"date"}]}
        ]}
        ```""".trimIndent())

        assertNotNull(result)
        assertTrue(result!!.document.blocks[0] is GenerativeUiBlock.PieChart)
        val form = result.document.blocks.last() as GenerativeUiBlock.Form
        assertEquals(FormFieldKind.SELECT, form.fields[0].kind)
        assertEquals(FormFieldKind.SLIDER, form.fields[1].kind)
        assertEquals(FormFieldKind.DATE, form.fields[2].kind)
    }

    @Test
    fun complianceMetricsTrackProviderRateAndReasons() {
        GenerativeUiComplianceMetrics.resetForTesting()
        val accepted = GenerativeUiCompiler.compileDetailed("```airi-ui\n{\"version\":1,\"blocks\":[{\"type\":\"stat\",\"id\":\"s\",\"value\":\"1\"}]}\n```")
        val rejected = GenerativeUiCompiler.compileDetailed("```airi-ui\n{\"version\":1,\"blocks\":[] }\n```")
        GenerativeUiComplianceMetrics.record("openai", accepted)
        GenerativeUiComplianceMetrics.record("openai", rejected)
        val snapshot = GenerativeUiComplianceMetrics.snapshot("openai")
        assertEquals(2L, snapshot.attempts)
        assertEquals(0.5, snapshot.acceptanceRate, 0.0001)
        assertEquals(1L, snapshot.rejectionReasons[GenerativeUiRejectReason.LIMIT_EXCEEDED.name.lowercase()])
    }
}
