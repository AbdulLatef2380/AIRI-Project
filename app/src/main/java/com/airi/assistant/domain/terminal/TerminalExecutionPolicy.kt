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
        optionViolation(binary, argv)?.let { return Decision(false, it) }
        if (argv.any(::isUnsafePathToken)) return Decision(false, "Path must remain inside the workspace")
        if (argv.any { it in setOf("-exec", "-execdir", "-delete") }) {
            return Decision(false, "Command option can escape the bounded executor")
        }
        if (argv.any { it.startsWith("--output") || it.startsWith("--work-tree") || it.startsWith("--git-dir") }) {
            return Decision(false, "Command option can escape the bounded executor")
        }
        return Decision(true, "allowed")
    }

    // ── Per-tool option allowlist (Report 3, T1) ──────────────────────────────
    // A binary being allowed is not enough: `find -fprint` writes files, `sed e`
    // and `sed s,a,b,e` execute commands, `sed w` writes, `awk -f` runs a script file,
    // `sort -o` writes, `git branch -D` mutates the repository. Every option token
    // must now be explicitly listed for its tool; anything else is refused.

    private class OptionRules(
        /** Letters allowed inside single-dash clusters such as `-la` (digits are always allowed in clusters). */
        val shortFlags: Set<Char> = emptySet(),
        /** Whole tokens allowed as written, e.g. `--quiet` or find's `-name`. */
        val exact: Set<String> = emptySet(),
        /** Long options accepted with an attached value, e.g. `--include=`. */
        val prefixes: List<String> = emptyList(),
    )

    private val optionRules: Map<String, OptionRules> = mapOf(
        "ls" to OptionRules(shortFlags = setOf('l', 'a', 'A', 'h', 'd', 't', 'r', 'S', 'R', '1')),
        "cat" to OptionRules(shortFlags = setOf('n', 'b', 's')),
        "echo" to OptionRules(exact = setOf("-n")),
        "head" to OptionRules(shortFlags = setOf('n', 'c', 'q', 'v')),
        "tail" to OptionRules(shortFlags = setOf('n', 'c', 'q', 'v')), // no -f / -F: they never return
        "wc" to OptionRules(shortFlags = setOf('l', 'w', 'c', 'm', 'L')),
        "sort" to OptionRules(shortFlags = setOf('r', 'n', 'u', 'f', 'b', 'h', 'V', 'k', 't')), // no -o
        "uniq" to OptionRules(shortFlags = setOf('c', 'd', 'u', 'i')),
        "grep" to OptionRules(
            shortFlags = setOf('n', 'i', 'v', 'r', 'R', 'l', 'L', 'c', 'w', 'x', 'E', 'F', 'H', 'h', 'o', 'q', 's', 'A', 'B', 'C'),
            exact = setOf(
                "--ignore-case", "--line-number", "--invert-match", "--recursive", "--count",
                "--files-with-matches", "--word-regexp", "--fixed-strings", "--extended-regexp",
                "--no-filename", "--with-filename", "--only-matching", "--quiet",
            ),
            prefixes = listOf("--include=", "--exclude=", "--exclude-dir="),
        ),
        "find" to OptionRules(
            exact = setOf(
                "-name", "-iname", "-type", "-maxdepth", "-mindepth", "-size", "-mtime", "-mmin",
                "-newer", "-empty", "-print", "-not", "-a", "-o", "-and", "-or", "-prune",
            ),
        ),
        // sed: only line-addressed print/delete/quit scripts; -i, -e, -f and --file are not listed.
        "sed" to OptionRules(
            shortFlags = setOf('n', 'E', 'r', 's'),
            exact = setOf("--quiet", "--silent", "--regexp-extended"),
        ),
        // awk: only -F; -f/--file/--source/-i/-l are not listed.
        "awk" to OptionRules(exact = setOf("-F")),
        "git" to OptionRules(
            shortFlags = setOf('n', 'p', 's', 'a', 'r', 'v', 'l'),
            exact = setOf(
                "--oneline", "--stat", "--name-only", "--name-status", "--short", "--branch", "--porcelain",
                "--cached", "--staged", "--list", "--show-current", "--merged", "--no-merged", "--contains",
                "--abbrev-ref", "--show-toplevel", "--graph", "--decorate", "--no-color", "--all",
            ),
            prefixes = listOf("--max-count=", "--since=", "--until=", "--author=", "--format=", "--pretty="),
        ),
    )

    private val sedScript = Regex("""^[0-9]+(,[0-9]+)?[pd]$|^[0-9]+q$""")
    private val numericArg = Regex("""^[-+]?[0-9]+$""")
    private val awkForbiddenFragments = listOf("ENVIRON", "system", "getline", "close", "fflush", "@")
    private val gitForbiddenPrefixes = listOf(
        "--ext-diff", "--textconv", "--output", "--work-tree", "--git-dir", "--exec-path",
        "--config", "--open-files-in-pager", "--pager", "--upload-pack", "-O",
    )

    /** Returns a refusal reason, or null when every option of [binary] is on its allowlist. */
    private fun optionViolation(binary: String, argv: List<String>): String? {
        val rules = optionRules[binary] ?: return "Binary is not available in the bounded workspace policy"
        val args = if (binary == "git") argv.drop(2) else argv.drop(1)
        val sub = if (binary == "git") argv.getOrNull(1) else null
        val refusal = "Option is not allowed for $binary in the bounded workspace policy"

        var afterDoubleDash = false
        val positionals = mutableListOf<String>()
        val tokenBeforePositional = mutableListOf<String?>()
        var previous: String? = null
        for (token in args) {
            if (afterDoubleDash) {
                positionals += token
                tokenBeforePositional += previous
            } else when {
                token == "--" -> afterDoubleDash = true
                numericArg.matches(token) -> {          // -20, +5, 1048576
                    positionals += token
                    tokenBeforePositional += previous
                }
                token.startsWith("-") -> {
                    if (binary == "git" && gitForbiddenPrefixes.any { token.startsWith(it) }) return refusal
                    val ok = token in rules.exact ||
                        rules.prefixes.any { token.startsWith(it) } ||
                        isAllowedCluster(token, rules.shortFlags)
                    if (!ok) return refusal
                }
                else -> {
                    positionals += token
                    tokenBeforePositional += previous
                }
            }
            previous = token
        }

        when (binary) {
            "sed" -> {
                val script = positionals.firstOrNull() ?: return "sed needs a line-addressed script such as 5p"
                if (!sedScript.matches(script)) return "Only line-addressed sed scripts (Np, N,Md, Nq) are allowed"
            }
            "awk" -> {
                if (argv.drop(1).any { token -> awkForbiddenFragments.any { it in token } }) return refusal
            }
            "git" -> if (sub == "branch" && "--list" !in args) {
                // `git branch <name>` creates a branch; a bare name is only valid as the value of these options.
                val valueTaking = setOf("--contains", "--merged", "--no-merged")
                if (tokenBeforePositional.any { it !in valueTaking }) return "git branch is limited to listing"
            }
        }
        return null
    }

    private fun isAllowedCluster(token: String, flags: Set<Char>): Boolean {
        if (flags.isEmpty() || token.length < 2 || token.startsWith("--")) return false
        val body = token.drop(1)
        return body.all { it in flags || it.isDigit() }
    }

    private fun isUnsafePathToken(token: String): Boolean {
        if (token.startsWith("/")) return true
        if (token.split('/').any { it == ".." }) return true
        if (token.contains('/') && !token.startsWith("./")) return true
        return false
    }

    private const val MAX_COMMAND_CHARS = 8_192
}
