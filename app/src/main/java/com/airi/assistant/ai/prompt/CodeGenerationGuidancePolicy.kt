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

    val SYSTEM_INSTRUCTION: String = """
        For code-generation, code-editing, or debugging requests, silently review the proposed result before responding. Check that it meets the requested behavior, is structurally complete, and has no obvious syntax, dependency, security, or edge-case problems; fix issues you can. Prefer safe input parsing over unrestricted eval/exec when applicable. Use an available isolated validation tool for meaningful, non-destructive checks when useful. Never claim that code was run, compiled, or tested unless a tool actually produced that evidence. Present complete, copyable code in language-labelled fenced blocks, and include necessary assumptions, dependencies, and run/test steps. Do not pad output to an arbitrary line count. If response limits prevent completion, clearly label the result as partial and split it into numbered parts with an explicit continuation point. Preserve the active response-language contract already present in this prompt.
    """.trimIndent()

    /**
     * Added only for requests classified as code tasks (the agent path with write_file). The
     * general instruction above says to paste code in chat, which would make the model skip the
     * file tool; this one makes the file the deliverable.
     */
    val CODE_TASK_INSTRUCTION: String = """
        CODE TASK: the user asked you to build a program. If the load_skill tool is available, first call it with name python_app (and also pydroid3_compat when the user mentions Pydroid or Android) to get the workflow, then follow it. Do not only paste the code in chat. Call the write_file tool to save the complete program as a file, with a plain file name and the correct extension (for example calculator.py), and put the full source in one call; never send empty content. After write_file succeeds, reply briefly: the file name, how to run it, and its main features. The app shows a file card with Save and Share buttons under your reply, so tell the user they can save or share the file from that card; never invent a file path and never say it was saved to Downloads. Never claim the code was run, compiled, or tested unless a tool returned that evidence. If write_file is unavailable or fails, say so plainly and then give the complete code in a language-labelled fenced block.
    """.trimIndent()

    fun appliesTo(request: String): Boolean {
        if (request.contains("```") || asciiCodeSignals.containsMatchIn(request)) return true
        val normalized = request.lowercase(Locale.ROOT)
        return localizedSignals.any(normalized::contains)
    }

    fun systemPromptSuffix(request: String, codeTask: Boolean = false): String = buildString {
        if (appliesTo(request)) append("\n\n").append(SYSTEM_INSTRUCTION)
        if (codeTask) append("\n\n").append(CODE_TASK_INSTRUCTION)
    }
}
