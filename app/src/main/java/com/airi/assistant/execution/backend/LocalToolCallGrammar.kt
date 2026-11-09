package com.airi.assistant.execution.backend

/**
 * Report أ-6: a local (llama.cpp) model only had the prompt asking for tool-call JSON, so a small
 * model could emit a name that does not exist or break the JSON. This builds a GBNF grammar that
 * the native sampler applies LAZILY: it stays off while the model writes ordinary text and
 * switches on only when the output starts a tool call (`{"tool_call` ...). From that point the
 * model can only produce valid JSON with a tool name taken from the admitted list.
 *
 * Pure Kotlin so the grammar text is unit-testable on the JVM.
 */
internal object LocalToolCallGrammar {
    /** Names that are safe to place inside a quoted GBNF literal. */
    private val SAFE_NAME = Regex("[A-Za-z0-9_.:-]{1,64}")

    /** Returns the grammar, or null when no admitted tool has a usable name. */
    fun build(toolNames: Collection<String>): String? {
        val names = toolNames.map { it.trim() }.filter { SAFE_NAME.matches(it) }.distinct()
        if (names.isEmpty()) return null
        val nameAlternatives = names.joinToString(" | ") { "\"\\\"$it\\\"\"" }
        return """
root ::= "{" ws ( "\"tool_call\"" ws ":" ws call | "\"tool_calls\"" ws ":" ws "[" ws call ( ws "," ws call )* ws "]" ) ws "}"
call ::= "{" ws ( "\"id\"" ws ":" ws string ws "," ws )? "\"name\"" ws ":" ws name ws "," ws "\"args\"" ws ":" ws object ws "}"
name ::= $nameAlternatives
object ::= "{" ws ( pair ( ws "," ws pair )* )? ws "}"
pair ::= string ws ":" ws value
value ::= object | array | string | number | "true" | "false" | "null"
array ::= "[" ws ( value ( ws "," ws value )* )? ws "]"
string ::= "\"" ( [^"\\\x00-\x1f] | "\\" ( ["\\/bfnrt] | "u" [0-9a-fA-F]{4} ) )* "\""
number ::= "-"? ( "0" | [1-9] [0-9]* ) ( "." [0-9]+ )? ( [eE] [-+]? [0-9]+ )?
ws ::= [ \t\n]{0,20}
""".trimStart()
    }
}
