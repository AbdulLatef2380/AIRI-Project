package com.airi.assistant.domain.terminal

/**
 * Pure command admission policy shared by the agent gateway and tests.
 * It is intentionally narrower than the binary allowlist: a command is
 * advertised only when its argv shape is safe before ProcessBuilder starts.
 */
object TerminalExecutionPolicy {
    const val DISABLED_REASON = "Terminal command is not available in the bounded workspace policy"
    const val APPROVAL_REQUIRED_REASON = "This workspace mutation requires explicit approval"

    data class Decision(
        val allowed: Boolean,
        val reason: String,
        val requiresApproval: Boolean = false
    )

    private val safeBinaries = setOf(
        "ls", "cat", "echo", "find", "grep", "sed", "awk", "head", "tail", "wc", "sort", "uniq", "git"
    )
    private val destructiveBinaries = setOf("mkdir", "cp", "mv", "rm", "zip", "unzip", "tar")
    private val gitReadOnly = setOf("status", "log", "diff", "show", "ls-files", "rev-parse", "branch")
    private val shellMeta = setOf(';', '&', '|', '`', '$', '>', '<', '\n', '\r', '\u0000', '*', '?', '[', ']', '(', ')', '\\', '"', '\'')

    fun evaluate(command: String): Decision {
        val normalized = command.trim()
        if (normalized.isBlank()) return Decision(false, "Terminal command is empty")
        if (normalized.length > MAX_COMMAND_CHARS) return Decision(false, "Terminal command exceeds the input limit")
        val argv = normalized.split(Regex("\\s+")).filter(String::isNotBlank)
        if (argv.any { token -> token.any(shellMeta::contains) }) {
            return Decision(false, "Shell metacharacters are not allowed")
        }
        val binary = argv.first()
        if (binary in destructiveBinaries) return Decision(false, APPROVAL_REQUIRED_REASON, requiresApproval = true)
        if (binary !in safeBinaries) return Decision(false, "Binary is not available in the bounded workspace policy")
        if (binary == "git" && argv.getOrNull(1) !in gitReadOnly) {
            return Decision(false, "Only read-only git subcommands are available")
        }
        if (argv.any(::isUnsafePathToken)) return Decision(false, "Path must remain inside the workspace")
        if (argv.any { it in setOf("-exec", "-execdir", "-delete") }) {
            return Decision(false, "Command option can escape the bounded executor")
        }
        if (argv.any { it.startsWith("--output") || it.startsWith("--work-tree") || it.startsWith("--git-dir") }) {
            return Decision(false, "Command option can escape the bounded executor")
        }
        return Decision(true, "allowed")
    }

    private fun isUnsafePathToken(token: String): Boolean {
        if (token.startsWith("/")) return true
        if (token.split('/').any { it == ".." }) return true
        if (token.contains('/') && !token.startsWith("./")) return true
        return false
    }

    private const val MAX_COMMAND_CHARS = 8_192
}
