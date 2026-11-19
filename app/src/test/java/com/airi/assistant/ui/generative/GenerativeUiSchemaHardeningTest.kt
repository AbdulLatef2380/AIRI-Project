package com.airi.assistant.ui.generative

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class GenerativeUiSchemaHardeningTest {
    @Before
    fun resetCache() = GenerativeUiCompiler.clearCacheForTesting()

    private fun ui(json: String) = "```airi-ui\n$json\n```"

    private fun reasonOf(json: String): GenerativeUiRejectReason =
        (GenerativeUiCompiler.compileDetailed(ui(json)) as GenerativeUiCompileResult.Rejected).reason

    @Test
    fun everyRejectionCarriesItsTypedReason() {
        val cases = mapOf(
            """{"version":1,"extra":1,"blocks":[]}""" to GenerativeUiRejectReason.UNKNOWN_KEY,
            """{"version":2,"blocks":[{"type":"stat","id":"a","value":"1"}]}""" to GenerativeUiRejectReason.BAD_VERSION,
            """{"version":"x","blocks":[{"type":"stat","id":"a","value":"1"}]}""" to GenerativeUiRejectReason.BAD_VERSION,
            """{"version":1,"blocks":[]}""" to GenerativeUiRejectReason.LIMIT_EXCEEDED,
            """{"version":1,"blocks":[{"type":"widget","id":"a"}]}""" to GenerativeUiRejectReason.UNKNOWN_BLOCK,
            """{"version":1,"blocks":[{"type":"stat","id":"bad id","value":"1"}]}""" to GenerativeUiRejectReason.BAD_ID,
            """{"version":1,"blocks":[{"type":"stat","id":"a","value":"1"},{"type":"stat","id":"a","value":"2"}]}""" to GenerativeUiRejectReason.DUPLICATE_ID,
            """{"version":1,"blocks":[{"id":"a","value":"1"}]}""" to GenerativeUiRejectReason.INVALID_SCHEMA,
            """{"version":1,"blocks":[{"type":"table","id":"t","headers":["A"],"rows":[["1"]]}]}""" to GenerativeUiRejectReason.LIMIT_EXCEEDED,
            """{"version":1,"blocks":[{"type":"bar_chart","id":"c","labels":["a"],"values":[1,2]}]}""" to GenerativeUiRejectReason.INVALID_SCHEMA,
            """{"version":1,"blocks":[{"type":"route","id":"r","stops":[{"id":"a","label":"A","location":"X","latitude":99},{"id":"b","label":"B","location":"Y"}]}]}""" to GenerativeUiRejectReason.LIMIT_EXCEEDED,
        )
        cases.forEach { (json, expected) -> assertEquals(json, expected, reasonOf(json)) }
    }

    @Test
    fun malformedJsonIsBadJsonAndRawTextIsNotLeaked() {
        val result = GenerativeUiCompiler.compileDetailed(ui("{ not json secret-token"))
        assertEquals(GenerativeUiRejectReason.BAD_JSON, (result as GenerativeUiCompileResult.Rejected).reason)
        assertFalse(result.toString().contains("secret-token"))
    }

    @Test
    fun formActionIdCannotCollideWithActionOptionOrAnotherForm() {
        val withOption = """{"version":1,"blocks":[
            {"type":"form","id":"f1","fields":[{"id":"x","label":"X"}],"actionId":"go"},
            {"type":"actions","id":"a1","options":[{"id":"go","label":"Go","prompt":"go"}]}]}"""
        val twoForms = """{"version":1,"blocks":[
            {"type":"form","id":"f1","fields":[{"id":"x","label":"X"}],"actionId":"send"},
            {"type":"form","id":"f2","fields":[{"id":"y","label":"Y"}],"actionId":"send"}]}"""
        assertEquals(GenerativeUiRejectReason.DUPLICATE_ID, reasonOf(withOption))
        assertEquals(GenerativeUiRejectReason.DUPLICATE_ID, reasonOf(twoForms))
    }

    @Test
    fun explicitFormActionIdCannotCollideWithItsBlockId() {
        val sameAsBlock = """{"version":1,"blocks":[
            {"type":"form","id":"form","fields":[{"id":"name","label":"Name"}],"actionId":"form"}]}"""
        assertEquals(GenerativeUiRejectReason.DUPLICATE_ID, reasonOf(sameAsBlock))
    }

    @Test
    fun structuredOutputAndCompilerShareStrictIdLimit() {
        assertTrue(GenerativeUiStructuredOutput.SPEC.strict)
        assertTrue(
            GenerativeUiStructuredOutput.SPEC.schemaJson.contains(
                "{1,${GenerativeUiLimits.MAX_ID_CHARS}}",
            ),
        )
        val oversizedId = "a".repeat(GenerativeUiLimits.MAX_ID_CHARS + 1)
        assertEquals(
            GenerativeUiRejectReason.BAD_ID,
            reasonOf("""{"version":1,"blocks":[{"type":"stat","id":"$oversizedId","value":"1"}]}"""),
        )
    }

    @Test
    fun formWithDefaultActionIdStillCompilesAndRebuilds() {
        val text = ui("""{"version":1,"blocks":[{"type":"form","id":"calc","fields":[{"id":"w","label":"W","required":true}]}]}""")
        assertNotNull(GenerativeUiCompiler.compile(text))
        val rebuilt = GenerativeUiCompiler.rebuildAction(text, GenerativeUiAction("calc", mapOf("w" to "5")))
        assertEquals("calc", rebuilt?.id)
        assertNull(GenerativeUiCompiler.rebuildAction(text, GenerativeUiAction("calc", mapOf("forged" to "1"))))
        assertNull(GenerativeUiCompiler.rebuildAction(text, GenerativeUiAction("unknown")))
    }

    @Test
    fun numericTableCellsRemainAccepted() {
        val result = GenerativeUiCompiler.compile(ui("""{"version":1,"blocks":[{"type":"table","id":"t","headers":["A","B"],"rows":[["x",5]]}]}"""))
        assertNotNull(result)
    }

    @Test
    fun repeatedCompileReturnsTheSameCachedResult() {
        val text = ui("""{"version":1,"blocks":[{"type":"stat","id":"s","value":"1"}]}""")
        val first = GenerativeUiCompiler.compileDetailed(text)
        assertTrue(first === GenerativeUiCompiler.compileDetailed(text))
    }

    @Test
    fun withoutGenerativeUiRemovesClosedAndUnclosedPayloads() {
        val closed = "before\n" + ui("""{"version":1,"blocks":[{"type":"route","id":"r"}]}""") + "\nafter"
        val stripped = GenerativeUiCompiler.withoutGenerativeUi(closed)
        assertFalse(stripped.contains("route"))
        assertTrue(stripped.contains("before") && stripped.contains("after"))
        val unclosed = "intro\n```airi-ui\n{\"blocks\":[{\"type\":\"bar_chart\""
        assertEquals("intro", GenerativeUiCompiler.withoutGenerativeUi(unclosed).trim())
        assertEquals("plain", GenerativeUiCompiler.withoutGenerativeUi("plain"))
    }
}
