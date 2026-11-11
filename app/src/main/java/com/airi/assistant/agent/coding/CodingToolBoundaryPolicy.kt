package com.airi.assistant.agent.coding

import com.airi.assistant.domain.terminal.TerminalExecutionPolicy

/**
 * Pure admission policy for CodingAgent tool arguments.
 * It validates scope and resource bounds before a ToolExecutionPipeline handler
 * touches the workspace. It does not read files or start processes itself.
 */
class CodingToolBoundaryPolicy(
    private val bounds: Bounds = Bounds(),
) {
    data class Bounds(
        val maxPathChars: Int = 512,
        val maxFileBytes: Int = 1_048_576,
        val maxFilesPerListing: Int = 1_000,
        val maxSearchResults: Int = 200,
        val maxSearchQueryChars: Int = 256,
        val maxPatchBytes: Int = 512_000,
        val maxTerminalTimeoutMs: Long = 120_000L,
        val maxTerminalOutputChars: Int = 64_000,
    ) {
        init {
            require(maxPathChars > 0)
            require(maxFileBytes > 0)
            require(maxFilesPerListing > 0)
            require(maxSearchResults > 0)
            require(maxSearchQueryChars > 0)
            require(maxPatchBytes > 0)
            require(maxTerminalTimeoutMs > 0)
            require(maxTerminalOutputChars > 0)
        }
    }

    sealed interface Decision<out T> {
        data class Allowed<T>(val value: T) : Decision<T>
        data class Denied(val reason: String) : Decision<Nothing>
    }

    fun readFile(path: String, maxBytes: Int = bounds.maxFileBytes): Decision<String> =
        boundedPath(path).flatMap { normalized ->
            if (maxBytes in 1..bounds.maxFileBytes) {
                Decision.Allowed(normalized)
            } else {
                Decision.Denied("file_read_limit_exceeded")
            }
        }

    fun listFiles(path: String = "/", limit: Int = bounds.maxFilesPerListing): Decision<ListRequest> =
        boundedPath(path).flatMap { normalized ->
            if (limit in 1..bounds.maxFilesPerListing) {
                Decision.Allowed(ListRequest(normalized, limit))
            } else {
                Decision.Denied("file_listing_limit_exceeded")
            }
        }

    fun searchFiles(query: String, path: String = "/", limit: Int = bounds.maxSearchResults): Decision<SearchRequest> =
        boundedPath(path).flatMap { normalized ->
            when {
                query.isBlank() -> Decision.Denied("search_query_empty")
                query.length > bounds.maxSearchQueryChars -> Decision.Denied("search_query_limit_exceeded")
                limit !in 1..bounds.maxSearchResults -> Decision.Denied("search_result_limit_exceeded")
                else -> Decision.Allowed(SearchRequest(query, normalized, limit))
            }
        }

    fun writeFile(path: String, contentBytes: Int): Decision<WriteRequest> =
        boundedPath(path).flatMap { normalized ->
            if (contentBytes in 0..bounds.maxFileBytes) {
                Decision.Allowed(WriteRequest(normalized, contentBytes))
            } else {
                Decision.Denied("file_write_limit_exceeded")
            }
        }

    fun applyPatch(path: String, patchBytes: Int): Decision<PatchRequest> =
        boundedPath(path).flatMap { normalized ->
            if (patchBytes in 1..bounds.maxPatchBytes) {
                Decision.Allowed(PatchRequest(normalized, patchBytes))
            } else {
                Decision.Denied("patch_limit_exceeded")
            }
        }

    fun terminalExecute(
        command: String,
        workingDirectory: String,
        timeoutMs: Long,
        outputChars: Int,
    ): Decision<TerminalRequest> {
        val cwd = boundedPath(workingDirectory)
        if (cwd is Decision.Denied) return cwd
        val commandDecision = TerminalExecutionPolicy.evaluate(command)
        val approvalRequired = commandDecision.requiresApproval &&
            commandDecision.reason == TerminalExecutionPolicy.APPROVAL_REQUIRED_REASON
        if (!commandDecision.allowed && !approvalRequired) {
            return Decision.Denied("terminal_denied:${commandDecision.reason}")
        }
        if (approvalRequired && !isBoundedApprovalCommand(command)) {
            return Decision.Denied("terminal_denied:destructive_command_out_of_scope")
        }
        if (timeoutMs !in 1..bounds.maxTerminalTimeoutMs) return Decision.Denied("terminal_timeout_limit_exceeded")
        if (outputChars !in 1..bounds.maxTerminalOutputChars) return Decision.Denied("terminal_output_limit_exceeded")
        return Decision.Allowed(
            TerminalRequest(
                command = command.trim(),
                workingDirectory = (cwd as Decision.Allowed).value,
                timeoutMs = timeoutMs,
                outputChars = outputChars,
                requiresApproval = commandDecision.requiresApproval,
            ),
        )
    }

    /** Only simple relative-path mutations may reach the explicit approval gate. */
    private fun isBoundedApprovalCommand(command: String): Boolean {
        val argv = command.trim().split(Regex("\\s+")).filter(String::isNotBlank)
        if (argv.size < 2 || argv.drop(1).any { it.startsWith("-") || !isWorkspaceRelativePath(it) }) return false
        return when (argv.first()) {
            "rm", "mkdir" -> true
            "cp", "mv" -> argv.size == 3
            else -> false
        }
    }

    private fun isWorkspaceRelativePath(value: String): Boolean {
        if (value.isBlank() || value.length > bounds.maxPathChars || value.startsWith('/') ||
            value.contains('\\') || ':' in value || '\u0000' in value
        ) return false
        val normalized = value.removePrefix("./")
        return normalized.isNotBlank() && normalized.split('/').none { it == ".." || it.isBlank() }
    }

    fun workspacePath(path: String): Decision<String> = boundedPath(path)

    private fun boundedPath(path: String): Decision<String> {
        val candidate = path.trim().replace('\\', '/')
        if (candidate.isBlank()) return Decision.Denied("path_empty")
        if (candidate.length > bounds.maxPathChars) return Decision.Denied("path_length_exceeded")
        if ('\u0000' in candidate || ':' in candidate) return Decision.Denied("path_invalid")
        if (candidate.startsWith("//")) return Decision.Denied("path_outside_workspace")
        val segments = candidate.split('/').filter { it.isNotEmpty() && it != "." }
        if (segments.any { it == ".." }) return Decision.Denied("path_traversal")
        return Decision.Allowed("/" + segments.joinToString("/"))
    }

    private inline fun <T, R> Decision<T>.flatMap(transform: (T) -> Decision<R>): Decision<R> =
        when (this) {
            is Decision.Allowed -> transform(value)
            is Decision.Denied -> this
        }
}

data class ListRequest(val path: String, val limit: Int)
data class SearchRequest(val query: String, val path: String, val limit: Int)
data class WriteRequest(val path: String, val contentBytes: Int)
data class PatchRequest(val path: String, val patchBytes: Int)
data class TerminalRequest(
    val command: String,
    val workingDirectory: String,
    val timeoutMs: Long,
    val outputChars: Int,
    val requiresApproval: Boolean,
)
