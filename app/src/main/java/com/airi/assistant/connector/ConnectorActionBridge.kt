package com.airi.assistant.connector

import android.util.Log
import com.airi.assistant.core.ServiceLocator

/**
 * ConnectorActionBridge — translates agent plan-step action names into live
 * [ConnectorRegistry] calls.
 *
 * This is Tier 1.5 inside [com.airi.assistant.agent.execution.command.CommandRouter].
 * It sits between the accessibility-alias tier (UI gestures) and the
 * SubAgentRegistry tier (capability routing), intercepting well-known connector
 * action names before they reach keyword scoring.
 *
 * ## Design contract
 * - [handles] is a pure, zero-allocation predicate the caller uses for
 *   fast early-exit without touching the registry.
 * - [dispatch] does the real work: resolves the connector, builds
 *   [ConnectorInput], executes, and returns the raw [ConnectorOutput].
 * - The bridge is intentionally lenient on the *left* side (the LLM-emitted
 *   alias) and strict on the *right* side (the exact connector action string).
 *   This lets the LLM emit "read_file" or "file_read" while the connector
 *   always receives the canonical "read_text".
 * - Failures are returned as [ConnectorOutput.Failure] rather than thrown so
 *   CommandRouter can decide whether to continue or abort the plan.
 *
 * ## Supported connectors (Tier 1.5)
 * | Connector id     | Aliases handled                                           |
 * |------------------|-----------------------------------------------------------|
 * | `filesystem`     | read_file, write_file, append_file, list_dir, file_exists,|
 * |                  | delete_file, make_dir                                     |
 * | `terminal`       | exec, run_shell, shell, run_command, which, env, uname    |
 * | `http_api`       | http_get, http_post, http_put, http_delete                |
 * | `logcat`         | logcat_read, logcat_errors, read_airi_proof               |
 * | `git`            | git_status, git_log, git_diff, git_commit, git_pull,      |
 * |                  | git_branch                                                |
 * | `device_control` | get_clipboard, set_clipboard, get_volume, get_device_info, |
 * |                  | get_wifi, battery_status (via system_info)                 |
 * | `system_info`    | battery_status, network_status                            |
 */
object ConnectorActionBridge {

    private const val TAG = "ConnectorActionBridge"

    private val HANDLED_ACTIONS = setOf(
        // filesystem
        "read_file", "file_read", "read_text",
        "write_file", "file_write", "write_text",
        "append_file", "file_append", "append_text",
        "list_dir", "list_files", "ls", "dir",
        "file_exists", "exists",
        "delete_file", "remove_file", "rm",
        "make_dir", "mkdir", "create_dir",
        // terminal
        "exec", "run_shell", "shell", "run_command", "command",
        "which", "env", "uname", "pwd",
        // http
        "http_get", "get_url", "fetch_url", "web_get",
        "http_post", "post_url", "web_post",
        "http_put", "put_url",
        "http_delete", "delete_url",
        // logcat
        "logcat_read", "read_logs", "read_logcat", "recent_logs",
        "logcat_errors", "read_errors", "error_logs",
        "read_airi_proof", "airi_proof_log",
        // git
        "git_status", "git status",
        "git_log", "git log",
        "git_diff", "git diff",
        "git_commit", "git commit",
        "git_pull", "git pull",
        "git_branch", "git branch",
        // device_control
        "get_clipboard", "clipboard_read", "read_clipboard",
        "set_clipboard", "clipboard_write", "write_clipboard", "copy_to_clipboard",
        "get_volume", "volume",
        "get_wifi", "wifi_state", "wifi_status",
        "get_device_info", "device_info", "device_details",
        // system_info
        "battery_status", "battery", "battery_info",
        "network_status", "network_info", "connectivity",
    )

    /**
     * Returns true if this bridge can handle [action] without touching the
     * registry. Use as a fast check before calling [dispatch].
     */
    fun handles(action: String): Boolean =
        action.lowercase().trim() in HANDLED_ACTIONS

    /**
     * Dispatch [action] through the registered connector.
     *
     * @param action   Raw action string from the LLM plan step.
     * @param params   Key-value parameters from the plan step.
     * @param text     Free-text payload (file content, HTTP body, shell command, …).
     * @return [ConnectorOutput] when the action was handled; null if the registry
     *         is unavailable or the action is unknown.
     */
    suspend fun dispatch(
        action: String,
        params: Map<String, String>,
        text: String = "",
    ): ConnectorOutput? {
        val registry = runCatching { ServiceLocator.connectorRegistry }.getOrNull()
            ?: run {
                Log.w(TAG, "CONNECTOR_REGISTRY_UNAVAILABLE action=$action")
                return null
            }

        val route = resolve(action.lowercase().trim(), params, text)
            ?: return null

        val connector = registry.get(route.connectorId) ?: run {
            Log.w(TAG, "CONNECTOR_MISSING id=${route.connectorId} action=$action")
            return null
        }

        Log.i("AIRI_PROOF", "CONNECTOR_DISPATCH action=$action → ${route.connectorId}::${route.connectorInput.action} params=${route.connectorInput.params.keys}")

        val output = runCatching {
            connector.execute(route.connectorInput)
        }.getOrElse { e ->
            Log.e(TAG, "CONNECTOR_EXCEPTION id=${route.connectorId} action=$action: ${e.message}")
            ConnectorOutput.Failure(
                code    = "exception",
                message = e.message ?: "Unknown exception in ${route.connectorId}",
            )
        }

        when (output) {
            is ConnectorOutput.Success ->
                Log.i("AIRI_PROOF", "CONNECTOR_OK id=${route.connectorId} action=$action len=${output.text.length}")
            is ConnectorOutput.Failure ->
                Log.w("AIRI_PROOF", "CONNECTOR_FAIL id=${route.connectorId} action=$action code=${output.code} msg=${output.message.take(80)}")
            is ConnectorOutput.Streaming ->
                Log.i("AIRI_PROOF", "CONNECTOR_STREAM id=${route.connectorId} action=$action")
        }

        return output
    }

    // ── Internal routing table ─────────────────────────────────────────────────

    private data class Route(val connectorId: String, val connectorInput: ConnectorInput)

    @Suppress("CyclomaticComplexMethod")
    private fun resolve(a: String, params: Map<String, String>, text: String): Route? = when {

        // ── Filesystem ─────────────────────────────────────────────────────────
        a in setOf("read_file", "file_read", "read_text") -> Route(
            "filesystem",
            ConnectorInput(
                action = "read_text",
                params = mapOf("path" to (params["path"] ?: params["file"] ?: text)),
            )
        )
        a in setOf("write_file", "file_write", "write_text") -> Route(
            "filesystem",
            ConnectorInput(
                action = "write_text",
                text   = params["content"] ?: params["text"] ?: text,
                params = mapOf("path" to (params["path"] ?: params["file"] ?: "")),
            )
        )
        a in setOf("append_file", "file_append", "append_text") -> Route(
            "filesystem",
            ConnectorInput(
                action = "append_text",
                text   = params["content"] ?: params["text"] ?: text,
                params = mapOf("path" to (params["path"] ?: params["file"] ?: "")),
            )
        )
        a in setOf("list_dir", "list_files", "ls", "dir") -> Route(
            "filesystem",
            ConnectorInput(
                action = "list_dir",
                params = mapOf("path" to (params["path"] ?: params["dir"] ?: "internal://")),
            )
        )
        a in setOf("file_exists", "exists") -> Route(
            "filesystem",
            ConnectorInput(
                action = "file_exists",
                params = mapOf("path" to (params["path"] ?: params["file"] ?: text)),
            )
        )
        a in setOf("delete_file", "remove_file", "rm") -> Route(
            "filesystem",
            ConnectorInput(
                action = "delete_file",
                params = mapOf("path" to (params["path"] ?: params["file"] ?: text)),
            )
        )
        a in setOf("make_dir", "mkdir", "create_dir") -> Route(
            "filesystem",
            ConnectorInput(
                action = "make_dir",
                params = mapOf("path" to (params["path"] ?: params["dir"] ?: text)),
            )
        )

        // ── Terminal ───────────────────────────────────────────────────────────
        a in setOf("exec", "run_shell", "shell", "run_command", "command") -> Route(
            "terminal",
            ConnectorInput(
                action = "exec",
                text   = params["command"] ?: params["cmd"] ?: text,
            )
        )
        a == "which" -> Route("terminal", ConnectorInput(action = "which", text = params["binary"] ?: text))
        a == "env"   -> Route("terminal", ConnectorInput(action = "env"))
        a == "uname" -> Route("terminal", ConnectorInput(action = "uname"))
        a == "pwd"   -> Route("terminal", ConnectorInput(action = "pwd"))

        // ── HTTP ───────────────────────────────────────────────────────────────
        a in setOf("http_get", "get_url", "fetch_url", "web_get") -> Route(
            "http_api",
            ConnectorInput(
                action = "get",
                params = params + mapOf("url" to (params["url"] ?: text)),
            )
        )
        a in setOf("http_post", "post_url", "web_post") -> Route(
            "http_api",
            ConnectorInput(
                action = "post",
                text   = params["body"] ?: params["payload"] ?: text,
                params = params + mapOf("url" to (params["url"] ?: "")),
            )
        )
        a in setOf("http_put", "put_url") -> Route(
            "http_api",
            ConnectorInput(
                action = "put",
                text   = params["body"] ?: params["payload"] ?: text,
                params = params + mapOf("url" to (params["url"] ?: "")),
            )
        )
        a in setOf("http_delete", "delete_url") -> Route(
            "http_api",
            ConnectorInput(
                action = "delete",
                params = params + mapOf("url" to (params["url"] ?: text)),
            )
        )

        // ── Logcat ─────────────────────────────────────────────────────────────
        a in setOf("logcat_read", "read_logs", "read_logcat", "recent_logs") -> Route(
            "logcat",
            ConnectorInput(
                action = "read_recent",
                params = mapOf("lines" to (params["lines"] ?: "50")),
            )
        )
        a in setOf("logcat_errors", "read_errors", "error_logs") -> Route(
            "logcat",
            ConnectorInput(
                action = "read_errors",
                params = mapOf("lines" to (params["lines"] ?: "50")),
            )
        )
        a in setOf("read_airi_proof", "airi_proof_log") -> Route(
            "logcat",
            ConnectorInput(
                action = "read_airi_proof",
                params = mapOf("lines" to (params["lines"] ?: "100")),
            )
        )

        // ── Git ────────────────────────────────────────────────────────────────
        a in setOf("git_status", "git status") -> Route(
            "git",
            ConnectorInput(
                action = "status",
                params = mapOf("repo_path" to (params["repo_path"] ?: params["path"] ?: "")),
            )
        )
        a in setOf("git_log", "git log") -> Route(
            "git",
            ConnectorInput(
                action = "log",
                params = mapOf(
                    "repo_path" to (params["repo_path"] ?: params["path"] ?: ""),
                    "limit"     to (params["limit"] ?: "10"),
                ),
            )
        )
        a in setOf("git_diff", "git diff") -> Route(
            "git",
            ConnectorInput(
                action = "diff",
                params = mapOf(
                    "repo_path" to (params["repo_path"] ?: params["path"] ?: ""),
                    "args"      to (params["args"] ?: ""),
                ),
            )
        )
        a in setOf("git_commit", "git commit") -> Route(
            "git",
            ConnectorInput(
                action = "commit",
                text   = params["message"] ?: text,
                params = mapOf("repo_path" to (params["repo_path"] ?: params["path"] ?: "")),
            )
        )
        a in setOf("git_pull", "git pull") -> Route(
            "git",
            ConnectorInput(
                action = "pull",
                params = mapOf("repo_path" to (params["repo_path"] ?: params["path"] ?: "")),
            )
        )
        a in setOf("git_branch", "git branch") -> Route(
            "git",
            ConnectorInput(
                action = "branch",
                params = mapOf("repo_path" to (params["repo_path"] ?: params["path"] ?: "")),
            )
        )

        // ── Device Control ─────────────────────────────────────────────────────
        a in setOf("get_clipboard", "clipboard_read", "read_clipboard") -> Route(
            "device_control",
            ConnectorInput(action = "get_clipboard"),
        )
        a in setOf("set_clipboard", "clipboard_write", "write_clipboard", "copy_to_clipboard") -> Route(
            "device_control",
            ConnectorInput(
                action = "set_clipboard",
                text   = params["text"] ?: params["content"] ?: text,
            )
        )
        a in setOf("get_volume", "volume") -> Route(
            "device_control",
            ConnectorInput(action = "get_volume"),
        )
        a in setOf("get_wifi", "wifi_state", "wifi_status") -> Route(
            "device_control",
            ConnectorInput(action = "get_wifi_state"),
        )
        a in setOf("get_device_info", "device_info", "device_details") -> Route(
            "device_control",
            ConnectorInput(action = "get_device_info"),
        )

        // ── System Info ────────────────────────────────────────────────────────
        a in setOf("battery_status", "battery", "battery_info") -> Route(
            "system_info",
            ConnectorInput(action = "battery_status"),
        )
        a in setOf("network_status", "network_info", "connectivity") -> Route(
            "system_info",
            ConnectorInput(action = "network_status"),
        )

        else -> null
    }
}
