package com.airi.assistant.agent.loop.tool

import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * Part 5 §4/§6: before/after snapshots of every file `write_file` changes, so the run screen can show
 * the Original and Diff tabs and step through the run (replay).
 *
 * Rules that keep this safe and small:
 *  - Plain JVM files, no Android types: one folder per execution, named by a hash of the execution id
 *    (the id never reaches the file system), three files per snapshot (`NNN.before`, `NNN.after`,
 *    `NNN.meta`). The `.meta` file is written last and acts as the commit marker, so a snapshot that
 *    was interrupted half-way is simply not listed.
 *  - Only names accepted by [AgentFileWriter.sanitizeName] are recorded, so a model-chosen name can
 *    never become a path.
 *  - Bounded: content is capped at [MAX_SNAPSHOT_CHARS], a run keeps at most [MAX_SNAPSHOTS_PER_RUN]
 *    snapshots, and only the newest [MAX_RUNS] runs are kept ([prune]).
 *  - Recording never throws and never blocks a write: the caller ignores a `false` result.
 *  - The contents are the same text the user already has in `agent_files/`; they are NOT passed
 *    through the trace redaction (that works on short previews, not on whole documents). They are
 *    deleted with the run, with "clear logs" and with account deletion.
 */
object AgentFileHistory {
    const val DIRECTORY_NAME = "agent_file_history"
    const val MAX_RUNS = 30
    const val MAX_SNAPSHOTS_PER_RUN = 60
    const val MAX_SNAPSHOT_CHARS = AgentFileWriter.MAX_CONTENT_CHARS

    private const val META = ".meta"
    private const val BEFORE = ".before"
    private const val AFTER = ".after"

    /** One recorded write. [before] is null when the file did not exist yet. */
    data class Snapshot(
        val sequence: Int,
        val timestampMs: Long,
        val fileName: String,
        val before: String?,
        val after: String,
    )

    /** Folder name for an execution; null for a blank id. Hashed so the id is never a path part. */
    internal fun folderName(executionId: String): String? {
        val id = executionId.trim()
        if (id.isEmpty()) return null
        val digest = MessageDigest.getInstance("SHA-256").digest(id.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.take(32)
    }

    /**
     * The current content of [rawName] inside [filesDirectory] (the `agent_files` folder), or null
     * when it does not exist, is not a plain allowed name, or is too large to snapshot.
     */
    fun readExisting(filesDirectory: File, rawName: String): String? {
        val name = AgentFileWriter.sanitizeName(rawName) ?: return null
        val root = runCatching { filesDirectory.canonicalFile }.getOrNull() ?: return null
        val file = runCatching { File(root, name).canonicalFile }.getOrNull() ?: return null
        if (file.parentFile != root || !file.isFile) return null
        if (file.length() > MAX_SNAPSHOT_CHARS.toLong() * 4L) return null
        return runCatching { file.readText(Charsets.UTF_8) }.getOrNull()?.take(MAX_SNAPSHOT_CHARS)
    }

    /**
     * Records one write. Returns false (and records nothing) for a blank execution id, a name the
     * writer would not accept, an unchanged file, or a run that already has [MAX_SNAPSHOTS_PER_RUN].
     */
    fun record(
        root: File,
        executionId: String,
        fileName: String,
        before: String?,
        after: String,
        nowMs: Long,
    ): Boolean {
        val folder = folderName(executionId) ?: return false
        val name = AgentFileWriter.sanitizeName(fileName) ?: return false
        if (before != null && before == after) return false
        return try {
            val dir = File(root, folder)
            if (!dir.exists() && !dir.mkdirs()) return false
            val existing = sequences(dir)
            if (existing.size >= MAX_SNAPSHOTS_PER_RUN) return false
            val seq = (existing.maxOrNull() ?: 0) + 1
            val stem = "%03d".format(seq)
            File(dir, stem + AFTER).writeText(after.take(MAX_SNAPSHOT_CHARS), Charsets.UTF_8)
            if (before != null) File(dir, stem + BEFORE).writeText(before.take(MAX_SNAPSHOT_CHARS), Charsets.UTF_8)
            // Commit marker last.
            File(dir, stem + META).writeText("ts=$nowMs\nname=$name\n", Charsets.UTF_8)
            dir.setLastModified(nowMs)
            prune(root, MAX_RUNS)
            true
        } catch (_: IOException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }

    /** All committed snapshots of one execution, oldest first. Unreadable entries are skipped. */
    fun load(root: File, executionId: String): List<Snapshot> {
        val folder = folderName(executionId) ?: return emptyList()
        val dir = File(root, folder)
        if (!dir.isDirectory) return emptyList()
        return sequences(dir).sorted().mapNotNull { seq ->
            runCatching {
                val stem = "%03d".format(seq)
                val meta = File(dir, stem + META).readLines(Charsets.UTF_8)
                    .mapNotNull { line -> line.indexOf('=').takeIf { it > 0 }?.let { line.substring(0, it) to line.substring(it + 1) } }
                    .toMap()
                val name = AgentFileWriter.sanitizeName(meta["name"].orEmpty()) ?: return@runCatching null
                val beforeFile = File(dir, stem + BEFORE)
                Snapshot(
                    sequence = seq,
                    timestampMs = meta["ts"]?.toLongOrNull() ?: 0L,
                    fileName = name,
                    before = if (beforeFile.isFile) beforeFile.readText(Charsets.UTF_8) else null,
                    after = File(dir, stem + AFTER).readText(Charsets.UTF_8),
                )
            }.getOrNull()
        }
    }

    fun delete(root: File, executionId: String) {
        val folder = folderName(executionId) ?: return
        runCatching { File(root, folder).deleteRecursively() }
    }

    /** Deletes every run folder except those of [keepExecutionIds] (runs still in progress). */
    fun clear(root: File, keepExecutionIds: Set<String> = emptySet()) {
        if (keepExecutionIds.isEmpty()) {
            runCatching { root.deleteRecursively() }
            return
        }
        val keep = keepExecutionIds.mapNotNull(::folderName).toSet()
        root.listFiles { f -> f.isDirectory && f.name !in keep }?.forEach { runCatching { it.deleteRecursively() } }
    }

    /** Keeps the [keep] most recently written run folders. */
    fun prune(root: File, keep: Int = MAX_RUNS) {
        val dirs = root.listFiles { f -> f.isDirectory }?.sortedByDescending { it.lastModified() } ?: return
        dirs.drop(keep.coerceAtLeast(0)).forEach { runCatching { it.deleteRecursively() } }
    }

    private fun sequences(dir: File): List<Int> =
        dir.listFiles { f -> f.isFile && f.name.endsWith(META) }
            ?.mapNotNull { it.name.removeSuffix(META).toIntOrNull() }
            .orEmpty()
}
