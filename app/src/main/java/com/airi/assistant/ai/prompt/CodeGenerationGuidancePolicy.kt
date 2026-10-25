package com.airi.assistant.ai.prompt

import java.util.Locale

/** Adds a concise, shared quality instruction only to code-oriented requests. */
object CodeGenerationGuidancePolicy {
    private val asciiCodeSignals = Regex(
        "(?i)\\b(?:code|source code|script|program|programming|function|class|algorithm|" +
            "python|kotlin|java|javascript|typescript|swift|rust|golang|go|c\\+\\+|c#|" +
            "html|css|sql|bash|shell|terminal|compile|debug|compiler|repository|api)\\b"
    )
    private val localizedSignals = listOf(
        "كود", "شفرة", "برمجة", "برنامج", "برمج", "سكربت", "بايثون", "جافا", "كوتلن", "جافاسكربت",
        "código", "codigo", "programa", "programación", "programacion", "guion", "función", "funcion", "clase",
        "代码", "程序", "脚本", "编程", "函数", "类", "编写",
    )

    const val SYSTEM_INSTRUCTION: String = """
        For code-generation, code-editing, or debugging requests, silently review the proposed result before responding. Check that it meets the requested behavior, is structurally complete, and has no obvious syntax, dependency, security, or edge-case problems; fix issues you can. Prefer safe input parsing over unrestricted eval/exec when applicable. Use an available isolated validation tool for meaningful, non-destructive checks when useful. Never claim that code was run, compiled, or tested unless a tool actually produced that evidence. Present complete, copyable code in language-labelled fenced blocks, and include necessary assumptions, dependencies, and run/test steps. Do not pad output to an arbitrary line count. If response limits prevent completion, clearly label the result as partial and split it into numbered parts with an explicit continuation point. Preserve the active response-language contract already present in this prompt.
    """.trimIndent()

    fun appliesTo(request: String): Boolean {
        if (request.contains("```") || asciiCodeSignals.containsMatchIn(request)) return true
        val normalized = request.lowercase(Locale.ROOT)
        return localizedSignals.any(normalized::contains)
    }

    fun systemPromptSuffix(request: String): String =
        if (appliesTo(request)) "\n\n$SYSTEM_INSTRUCTION" else ""
}
