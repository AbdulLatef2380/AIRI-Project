package com.airi.assistant.ai.skills.impl

import android.content.Context
import com.airi.assistant.ai.skills.AiriSkill
import com.airi.assistant.ai.skills.SkillContext
import com.airi.assistant.ai.skills.SkillMemoryAccess
import com.airi.assistant.ai.skills.SkillModelAccess
import com.airi.assistant.ai.skills.SkillResult
import com.airi.assistant.ai.skills.SkillToolDefinition
import com.airi.assistant.ai.skills.SkillParamDef

class CodeAssistantSkill(private val context: Context) : AiriSkill {

    override val skillId    = "code_assistant"
    override val name       = "code_assistant"
    override val description = "Write, explain, review, debug, and refactor code in any programming language"
    override val version    = "1.0.0"
    override val author     = "AIRI Official"
    override val category   = "DEVELOPER"
    override val iconEmoji  = ""
    override val isOfficial = true
    override val memoryAccess = SkillMemoryAccess.READ_ONLY
    override val modelAccess  = SkillModelAccess.CHAT

    override val parameters = mapOf(
        "task"     to "string — the coding task (write, explain, debug, review, refactor)",
        "code"     to "string (optional) — existing code to work with",
        "language" to "string (optional) — programming language"
    )

    override val toolDefinitions = listOf(
        SkillToolDefinition(
            name        = "code_assist",
            description = "Help with coding tasks: write, explain, debug, review, or refactor code",
            parameters  = mapOf(
                "task"     to SkillParamDef("string", "What to do with the code", required = true),
                "code"     to SkillParamDef("string", "Existing code snippet (optional)", required = false),
                "language" to SkillParamDef("string", "Programming language (optional)", required = false)
            )
        )
    )

    private val codeKeywords = listOf(
        "code", "program", "function", "class", "method", "script",
        "bug", "debug", "error", "fix", "implement", "write a",
        "create a function", "kotlin", "python", "javascript", "java",
        "typescript", "swift", "c++", "golang", "rust", "sql", "html",
        "css", "bash", "shell", "regex", "algorithm", "api", "endpoint",
        "refactor", "optimize", "review my", "explain this code"
    )

    override fun score(input: String, context: SkillContext): Int {
        val lower = input.lowercase()
        var score = 0
        codeKeywords.forEach { kw -> if (lower.contains(kw)) score += 12 }
        if (lower.contains("```") || lower.contains("def ") || lower.contains("fun ") ||
            lower.contains("class ") || lower.contains("function ") || lower.contains("import ")) {
            score += 30
        }
        if (context.lastUsedSkill == skillId) score += 10
        return score.coerceAtMost(100)
    }

    override suspend fun execute(params: Map<String, Any>): SkillResult {
        val start = System.currentTimeMillis()
        val skillCtx = params["context"] as? SkillContext
        val modelBridge = skillCtx?.modelBridge
            ?: return SkillResult(
                false, "",
                "Code assistance requires an active AI model. Load a model in Settings → AI Models.",
                skillId
            )

        val input    = params["input"] as? String ?: ""
        val task     = params["task"] as? String ?: input
        val code     = params["code"] as? String ?: extractCodeBlock(input)
        val language = params["language"] as? String ?: detectLanguage(input, code)

        val systemPrompt = """You are an expert software engineer and code assistant. 
You write clean, correct, well-documented code.
When given code to review or debug, identify issues clearly.
When writing new code, follow best practices for the language.
Always include brief explanations of what the code does and any important caveats.
Format code with proper syntax highlighting markers (```language ... ```)."""

        val prompt = buildString {
            if (language.isNotBlank()) append("Language: $language\n\n")
            if (code != null && code.isNotBlank()) {
                append("Code:\n```\n$code\n```\n\n")
                append("Task: $task")
            } else {
                append("Task: $task")
            }
        }

        return try {
            val response = modelBridge.complete(prompt, systemPrompt, maxTokens = 2048)
            SkillResult(
                success     = true,
                data        = response,
                skillName   = skillId,
                executionMs = System.currentTimeMillis() - start,
                metadata    = mapOf("language" to language)
            )
        } catch (e: Exception) {
            SkillResult(false, "", "Code assistant failed: ${e.message}", skillId)
        }
    }

    private fun extractCodeBlock(input: String): String? {
        val regex = Regex("""```[\w]*\n?([\s\S]*?)```""")
        return regex.find(input)?.groupValues?.get(1)?.trim()
    }

    private fun detectLanguage(input: String, code: String?): String {
        val lower = (input + " " + (code ?: "")).lowercase()
        return when {
            lower.contains("kotlin")      -> "Kotlin"
            lower.contains("python")      -> "Python"
            lower.contains("javascript")  -> "JavaScript"
            lower.contains("typescript")  -> "TypeScript"
            lower.contains("java")        -> "Java"
            lower.contains("swift")       -> "Swift"
            lower.contains("golang") || lower.contains(" go ")  -> "Go"
            lower.contains("rust")        -> "Rust"
            lower.contains("c++") || lower.contains("cpp") -> "C++"
            lower.contains("sql")         -> "SQL"
            lower.contains("bash") || lower.contains("shell") -> "Bash"
            lower.contains("html")        -> "HTML"
            lower.contains("css")         -> "CSS"
            else                          -> ""
        }
    }
}
