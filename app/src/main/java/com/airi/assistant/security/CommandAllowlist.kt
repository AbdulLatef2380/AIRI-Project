package com.airi.assistant.security

/**
 * CommandAllowlist — versioned, configurable allowlist of safe shell binaries.
 *
 * Extracted from [com.airi.assistant.connector.system.TerminalConnector] into
 * its own class so that:
 *   1. SecureExecutionPolicy can consult it independently of TerminalConnector.
 *   2. The list can be updated at runtime (e.g. from a remote config).
 *   3. The list is testable without standing up a full Connector.
 *
 * ── TIERS ────────────────────────────────────────────────────────────────
 *
 *   SAFE    — informational / read-only commands, no side effects
 *   CAUTION — commands that can write data (curl, wget, tar, gzip, base64)
 *   DENIED  — everything not in SAFE or CAUTION is implicitly denied
 *
 * ── ALLOWLIST MODEL ──────────────────────────────────────────────────────
 *
 *   Allow by exact binary name or by suffix match (e.g. "/system/bin/echo").
 *   Rejection uses code "not_allowed" which the TerminalConnector re-uses.
 */
object CommandAllowlist {

    const val VERSION = 3

    /**
     * Read-only / informational commands.
     * These cannot write files, establish persistent connections, or modify system state.
     */
    val SAFE: Set<String> = setOf(
        "echo", "cat", "ls", "pwd", "date", "which", "uname",
        "id", "env", "printenv", "whoami", "hostname",
        "df", "du", "free", "uptime", "ps",
        "find", "grep", "awk", "sed", "sort", "head", "tail", "wc",
        "sha256sum", "md5sum",
    )

    /**
     * Commands that can write data or establish network connections.
     * Require elevated policy clearance ([SecureExecutionPolicy.RiskLevel.MEDIUM] or higher).
     */
    val CAUTION: Set<String> = setOf(
        "curl", "wget", "ping",
        "tar", "gzip", "unzip",
        "base64",
    )

    /** Full allowed set (SAFE ∪ CAUTION). */
    val ALL: Set<String> = SAFE + CAUTION

    /** True if [binary] is in the SAFE tier. */
    fun isSafe(binary: String): Boolean = normBinary(binary) in SAFE

    /** True if [binary] is in the CAUTION tier. */
    fun isCaution(binary: String): Boolean = normBinary(binary) in CAUTION

    /** True if [binary] is in any allowed tier. */
    fun isAllowed(binary: String): Boolean = normBinary(binary) in ALL

    /**
     * Check a full command string. Extracts the binary name (first token)
     * and checks allowlist membership.
     */
    fun checkCommand(command: String): CheckResult {
        val tokens = command.trim().split("\\s+".toRegex())
        val binary  = tokens.firstOrNull() ?: return CheckResult.Denied("Empty command", "")
        val norm    = normBinary(binary)
        return when {
            norm in SAFE    -> CheckResult.Allowed(norm, RiskTier.SAFE)
            norm in CAUTION -> CheckResult.Allowed(norm, RiskTier.CAUTION)
            else            -> CheckResult.Denied(
                reason  = "Binary '$norm' is not on the allowed list (v$VERSION). Allowed: ${ALL.sorted().joinToString(", ")}",
                binary  = norm
            )
        }
    }

    private fun normBinary(binary: String): String =
        binary.substringAfterLast('/').trim().lowercase()

    enum class RiskTier { SAFE, CAUTION }

    sealed class CheckResult {
        data class Allowed(val binary: String, val tier: RiskTier) : CheckResult()
        data class Denied (val reason: String, val binary: String) : CheckResult()
    }
}
