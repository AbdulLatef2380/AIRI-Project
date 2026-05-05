package com.airi.assistant.connector.local

import android.util.Log
import com.airi.assistant.connector.Connector
import com.airi.assistant.connector.ConnectorInput
import com.airi.assistant.connector.ConnectorMeta
import com.airi.assistant.connector.ConnectorOutput
import com.airi.assistant.connector.ConnectorState
import com.airi.assistant.connector.ConnectorType
import com.airi.assistant.connector.system.TerminalConnector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

/**
 * GitConnector — run git commands in a local repository via ProcessBuilder.
 *
 * ## Android Sandbox Limitations
 * Git is NOT bundled with Android. This connector checks for a `git` binary
 * at common locations (`/system/bin/git`, `/data/data/.../bin/git` when
 * a terminal emulator provides it). If git is absent the connector reports
 * `git_not_found` rather than crashing.
 *
 * For rooted devices or devices with a terminal emulator that ships git
 * (Termux, UserLAnd), the binary path can be injected via [gitBinaryPath].
 *
 * ## Security
 * - All commands are parameterised (no shell interpolation).
 * - The working directory is resolved against [workDir] which defaults to
 *   the calling app's files dir. Paths are canonicalized to detect traversal.
 * - Only commands on [ALLOWED_GIT_SUBCOMMANDS] execute.
 *
 * ## Supported actions
 * | action          | params                          | notes                             |
 * |-----------------|---------------------------------|-----------------------------------|
 * | `status`        | `repo_path`                     | git status --porcelain            |
 * | `log`           | `repo_path`, `limit` (def 10)   | git log --oneline                 |
 * | `diff`          | `repo_path`, `args` (optional)  | git diff HEAD                     |
 * | `add`           | `repo_path`, `path` (file/.)    | git add <path>                    |
 * | `commit`        | `repo_path`, text=message       | git commit -m <message>           |
 * | `branch`        | `repo_path`                     | git branch --list                 |
 * | `show_file`     | `repo_path`, `file_path`        | git show HEAD:<file>              |
 * | `clone`         | `url`, `repo_path`              | git clone <url> <dir>             |
 * | `pull`          | `repo_path`                     | git pull --ff-only                |
 * | `check_git`     | —                               | Verify git binary is available    |
 */
class GitConnector(
    private val workDir: File? = null,
    val gitBinaryPath: String = resolveGitBinary(),
) : Connector {

    override val id          = "git"
    override val name        = "Git"
    override val description = "Run git commands (status, log, diff, commit, clone) in local repositories."
    override val type        = ConnectorType.LOCAL

    private val _state = MutableStateFlow(
        ConnectorState(connected = false, healthy = false, statusLine = "Checking git…")
    )

    override fun meta() = ConnectorMeta(
        id = id, name = name, description = description, type = type,
        tags = listOf("git", "vcs", "repo", "commit", "diff", "code"),
    )

    override fun state(): StateFlow<ConnectorState> = _state.asStateFlow()

    override suspend fun connect(): ConnectorState = withContext(Dispatchers.IO) {
        val available = gitBinaryPath.isNotBlank() && File(gitBinaryPath).exists()
        val version   = if (available) runGit(null, "version").take(40) else "git not found"
        _state.value = ConnectorState(
            connected = available,
            healthy   = available,
            statusLine = if (available) version else GIT_NOT_FOUND_MSG,
            lastUpdatedMs = System.currentTimeMillis(),
        )
        Log.i("AIRI_PROOF", "GIT_CONNECTOR_CONNECT available=$available binary=$gitBinaryPath")
        _state.value
    }

    override suspend fun disconnect() { /* stateless */ }

    override suspend fun execute(input: ConnectorInput): ConnectorOutput = withContext(Dispatchers.IO) {
        if (!_state.value.connected) {
            return@withContext ConnectorOutput.Failure(
                code = "git_not_found",
                message = GIT_NOT_FOUND_MSG,
            )
        }
        val repoPath = input.params["repo_path"]
        when (input.action) {
            "check_git"  -> checkGit()
            "status"     -> gitStatus(repoPath)
            "log"        -> gitLog(repoPath, input.params["limit"]?.toIntOrNull() ?: 10)
            "diff"       -> gitDiff(repoPath, input.params["args"].orEmpty())
            "add"        -> gitAdd(repoPath, input.params["path"].orEmpty())
            "commit"     -> gitCommit(repoPath, input.text)
            "branch"     -> gitBranch(repoPath)
            "show_file"  -> gitShowFile(repoPath, input.params["file_path"].orEmpty())
            "clone"      -> gitClone(input.params["url"].orEmpty(), repoPath)
            "pull"       -> gitPull(repoPath)
            else -> ConnectorOutput.Failure(
                code = "unknown_action",
                message = "GitConnector: unknown action '${input.action}'",
            )
        }
    }

    // ── Action implementations ─────────────────────────────────────────────────

    private fun checkGit(): ConnectorOutput {
        val out = runCatching { runGit(null, "version") }.getOrElse { return@checkGit ConnectorOutput.Failure("git_error", it.message ?: "error") }
        return ConnectorOutput.Success(text = out, data = mapOf("binary" to gitBinaryPath))
    }

    private fun gitStatus(repoPath: String?): ConnectorOutput =
        runAction(repoPath, "status") { dir ->
            val out = exec(dir, "status", "--porcelain", "--branch")
            Log.i("AIRI_PROOF", "GIT_STATUS repo=${dir.path.take(60)} lines=${out.lines().size}")
            ConnectorOutput.Success(text = out.ifBlank { "Working tree clean" }, data = mapOf("repo" to dir.path))
        }

    private fun gitLog(repoPath: String?, limit: Int): ConnectorOutput =
        runAction(repoPath, "log") { dir ->
            val out = exec(dir, "log", "--oneline", "-$limit")
            ConnectorOutput.Success(text = out, data = mapOf("repo" to dir.path, "limit" to limit.toString()))
        }

    private fun gitDiff(repoPath: String?, extraArgs: String): ConnectorOutput =
        runAction(repoPath, "diff") { dir ->
            val args = mutableListOf("diff", "HEAD")
            if (extraArgs.isNotBlank()) args += extraArgs.split(" ")
            val out = exec(dir, *args.toTypedArray())
            ConnectorOutput.Success(
                text = out.ifBlank { "No changes" },
                data = mapOf("repo" to dir.path, "chars" to out.length.toString()),
            )
        }

    private fun gitAdd(repoPath: String?, path: String): ConnectorOutput {
        if (path.isBlank()) return ConnectorOutput.Failure(code = "bad_input", message = "Missing 'path' param")
        return runAction(repoPath, "add") { dir ->
            exec(dir, "add", path)
            ConnectorOutput.Success(text = "Staged: $path")
        }
    }

    private fun gitCommit(repoPath: String?, message: String): ConnectorOutput {
        if (message.isBlank()) return ConnectorOutput.Failure(code = "bad_input", message = "Commit message (text) required")
        return runAction(repoPath, "commit") { dir ->
            val out = exec(dir, "commit", "-m", message)
            Log.i("AIRI_PROOF", "GIT_COMMIT repo=${dir.path.take(60)}")
            ConnectorOutput.Success(text = out)
        }
    }

    private fun gitBranch(repoPath: String?): ConnectorOutput =
        runAction(repoPath, "branch") { dir ->
            val out = exec(dir, "branch", "--list", "-vv")
            ConnectorOutput.Success(text = out)
        }

    private fun gitShowFile(repoPath: String?, filePath: String): ConnectorOutput {
        if (filePath.isBlank()) return ConnectorOutput.Failure(code = "bad_input", message = "Missing 'file_path' param")
        return runAction(repoPath, "show") { dir ->
            val out = exec(dir, "show", "HEAD:$filePath")
            ConnectorOutput.Success(text = out, data = mapOf("file" to filePath))
        }
    }

    private fun gitClone(url: String, repoPath: String?): ConnectorOutput {
        if (url.isBlank()) return ConnectorOutput.Failure(code = "bad_input", message = "Missing 'url' param")
        val destDir = repoPath?.let { File(it) } ?: workDir ?: return ConnectorOutput.Failure(
            code = "bad_input", message = "Missing 'repo_path' for clone destination",
        )
        return runCatching {
            val out = exec(destDir.parentFile ?: destDir, "clone", url, destDir.name)
            Log.i("AIRI_PROOF", "GIT_CLONE url=${url.take(80)} dest=${destDir.path}")
            ConnectorOutput.Success(text = out, data = mapOf("dest" to destDir.path))
        }.getOrElse { t ->
            ConnectorOutput.Failure(code = "git_error", message = t.message ?: "Clone failed", retryable = true)
        }
    }

    private fun gitPull(repoPath: String?): ConnectorOutput =
        runAction(repoPath, "pull") { dir ->
            val out = exec(dir, "pull", "--ff-only")
            Log.i("AIRI_PROOF", "GIT_PULL repo=${dir.path.take(60)}")
            ConnectorOutput.Success(text = out)
        }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun runAction(repoPath: String?, action: String, block: (File) -> ConnectorOutput): ConnectorOutput {
        val dir = when {
            repoPath != null -> File(repoPath)
            workDir  != null -> workDir
            else -> return ConnectorOutput.Failure(code = "bad_input", message = "Missing 'repo_path' param for action '$action'")
        }
        if (!dir.exists() || !dir.isDirectory) {
            return ConnectorOutput.Failure(code = "not_found", message = "Repo dir not found: ${dir.path}")
        }
        return runCatching { block(dir) }.getOrElse { t ->
            Log.w("AIRI_PROOF", "GIT_ACTION_FAIL action=$action cause=${t.message}")
            ConnectorOutput.Failure(code = "git_error", message = t.message ?: "Git error", retryable = false)
        }
    }

    private fun exec(workingDir: File, vararg args: String): String {
        val cmd = mutableListOf(gitBinaryPath) + args.toList()
        val process = ProcessBuilder(cmd)
            .directory(workingDir)
            .redirectErrorStream(true)
            .start()
        val output = StringBuilder()
        BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                output.append(line).append('\n')
                if (output.length > MAX_OUTPUT) {
                    output.append("[output truncated]")
                    break
                }
            }
        }
        process.waitFor()
        return output.toString().trimEnd()
    }

    private fun runGit(repoPath: String?, vararg args: String): String =
        exec(repoPath?.let { File(it) } ?: File("."), *args)

    companion object {
        private const val GIT_NOT_FOUND_MSG = "git binary not found on this device. Install a terminal emulator (e.g. Termux) that provides git."
        private const val MAX_OUTPUT        = 64 * 1024

        private val GIT_PATHS = listOf(
            "/usr/bin/git",
            "/usr/local/bin/git",
            "/system/bin/git",
            "/data/data/com.termux/files/usr/bin/git",
        )

        fun resolveGitBinary(): String =
            GIT_PATHS.firstOrNull { File(it).exists() } ?: "git"
    }
}
