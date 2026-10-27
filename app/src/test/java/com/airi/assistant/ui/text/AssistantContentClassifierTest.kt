package com.airi.assistant.ui.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantContentClassifierTest {
    @Test fun plainTextIsPlain() {
        assertEquals(AssistantContentKind.PLAIN_TEXT, AssistantContentClassifier.analyse("Hello").kind)
    }

    @Test fun markdownIsMarkdown() {
        assertEquals(AssistantContentKind.MARKDOWN, AssistantContentClassifier.analyse("# Heading\n\n**bold**").kind)
    }

    @Test fun fencedCodeIsCodeBlock() {
        val result = AssistantContentClassifier.analyse("```kotlin\nval airi = 1\n```")
        assertEquals(AssistantContentKind.CODE_BLOCK, result.kind)
        assertEquals("kotlin", result.codeBlocks.single().language)
        assertTrue(result.codeBlocks.single().code.contains("val airi"))
    }

    @Test fun jsonIsStructuredCode() {
        assertEquals(AssistantContentKind.STRUCTURED_CODE, AssistantContentClassifier.analyse("{\"model\":\"airi\"}").kind)
    }

    @Test fun markdownTableIsTable() {
        val table = "| Model | Status |\n|---|---|\n| Local | Ready |"
        assertEquals(AssistantContentKind.TABLE, AssistantContentClassifier.analyse(table).kind)
    }

    @Test fun longMarkdownUsesRichContentClassification() {
        assertEquals(AssistantContentKind.RICH_CONTENT, AssistantContentClassifier.analyse("# Long\n\n" + "text ".repeat(150)).kind)
    }

    @Test fun longMixedReplyRetainsCopyableCodeBlockMetadata() {
        val code = "import tkinter as tk\n\nclass Calculator:\n    def __init__(self):\n        self.root = tk.Tk()"
        val response = "شرح عربي يسبق المثال.\n```python\n$code\n```\n" + "شرح إضافي ".repeat(100)

        val analysis = AssistantContentClassifier.analyse(response)

        assertEquals(AssistantContentKind.RICH_CONTENT, analysis.kind)
        assertTrue(analysis.containsCode)
        assertEquals("python", analysis.codeBlocks.single().language)
        assertTrue(analysis.codeBlocks.single().code.contains("class Calculator"))
    }

    @Test fun completeCalculatorReplyKeepsIndependentFullCodeArtifact() {
        val response = "شرح مختصر.\n```python\nimport math\ndef calculate(expression):\n    return eval(expression, {\"__builtins__\": {}}, {\"math\": math})\n\nprint(calculate(\"2 + 2\"))\n```\nملاحظات التشغيل منفصلة."
        val analysis = AssistantContentClassifier.analyse(response)
        assertTrue(analysis.containsCode)
        assertEquals("python", analysis.codeBlocks.single().language)
        assertTrue(analysis.codeBlocks.single().code.contains("def calculate"))
        assertTrue(analysis.codeBlocks.single().code.contains("print(calculate"))
    }

    @Test fun multipleArtifactsRemainIndependentlyCopyable() {
        val response = "شرح\n```python\nprint(1)\n```\nثم إعدادات\n```json\n{\"ready\": true}\n```"
        val blocks = AssistantContentClassifier.analyse(response).codeBlocks
        assertEquals(2, blocks.size)
        assertEquals("python", blocks[0].language)
        assertEquals("json", blocks[1].language)
    }

    @Test fun mixedDirectionIsDetected() {
        assertEquals(AssistantContentKind.MIXED_DIRECTION, AssistantContentClassifier.analyse("هذا code إنجليزي").kind)
        assertEquals(AssistantContentKind.RTL_TEXT, AssistantContentClassifier.analyse("هذا نص عربي").direction)
    }

    @Test fun arabicWithCodeKeepsStreamingAndCodeSignals() {
        val result = AssistantContentClassifier.analyse("شرح عربي\n```kotlin\nval x = 1", isStreaming = true)
        assertEquals(AssistantContentKind.STREAMING_CONTENT, result.kind)
        assertTrue(result.containsCode)
        assertEquals(false, result.codeBlocks.single().isComplete)
    }

    @Test fun emptyResponseIsEmptyState() {
        assertEquals(AssistantContentKind.EMPTY_STATE, AssistantContentClassifier.analyse(" ").kind)
    }
}
