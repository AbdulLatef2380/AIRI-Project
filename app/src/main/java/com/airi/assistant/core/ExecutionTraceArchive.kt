package com.airi.assistant.core

import com.airi.assistant.domain.logging.LoggingService
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.Executors

/**
 * Durable copy of each assistant message's execution trace, keyed by the Room
 * message id. Without it the inline trace on a message disappears once the
 * process dies, because [ChatMessage.executionTraceSnapshot] is UI-only.
 *
 * File-backed rather than a Room column so no schema migration touches chat
 * data. Events were already redacted by [ExecutionStatusBus] before they get here.
 * Reads are lazy and must run off the main thread.
 */
object ExecutionTraceArchive {

    @Volatile var maxMessages = 200

    private val lock = Any()
    private var file: File? = null
    private var loaded = false
    private var writeGeneration = 0L
    private val entries = LinkedHashMap<Long, List<ExecutionTraceEvent>>()
    private val writer by lazy {
        Executors.newSingleThreadExecutor { r -> Thread(r, "execution-trace-archive").apply { isDaemon = true } }
    }

    fun attach(target: File) = synchronized(lock) {
        file = target
        loaded = false
        writeGeneration += 1
        entries.clear()
    }

    /** Stores the trace for a message; no-op for an empty trace or before [attach]. */
    fun put(messageId: Long, events: List<ExecutionTraceEvent>) {
        if (events.isEmpty()) return
        val snapshot = synchronized(lock) {
            val target = file ?: return
            if (!ensureLoaded(target)) return
            entries.remove(messageId)
            entries[messageId] = events
            while (entries.size > maxMessages) entries.remove(entries.keys.first())
            writeGeneration += 1
            writeGeneration to entries.toMap()
        }
        writer.execute { write(snapshot.second, snapshot.first) }
    }

    /** Returns stored traces for the given message ids. Call off the main thread. */
    fun getAll(messageIds: Collection<Long>): Map<Long, List<ExecutionTraceEvent>> = synchronized(lock) {
        val target = file ?: return emptyMap()
        if (!ensureLoaded(target)) return emptyMap()
        messageIds.mapNotNull { id -> entries[id]?.let { id to it } }.toMap()
    }

    /** Deletes the durable trace belonging to one chat message. */
    fun deleteMessage(messageId: Long) {
        if (messageId <= 0L) return
        removeEntries { it == messageId }
    }

    /** Deletes every durable trace whose events belong to the given chat session. */
    fun deleteSession(sessionId: String) {
        val normalized = sessionId.trim()
        if (normalized.isBlank()) return
        removeEntries { id -> entries[id].orEmpty().any { it.sessionId == normalized } }
    }

    /** Removes all archived traces as part of account deletion. */
    fun clear() = synchronized(lock) {
        entries.clear()
        loaded = true
        writeGeneration += 1
        file?.let { target ->
            runCatching { target.delete() }
                .onFailure { LoggingService.error("AIRI_TRACE", "Execution trace archive clear failed", it) }
        }
    }

    private fun removeEntries(predicate: (Long) -> Boolean) {
        val snapshot = synchronized(lock) {
            val target = file ?: return
            if (!ensureLoaded(target)) return
            val removed = entries.keys.filter(predicate)
            if (removed.isEmpty()) return
            removed.forEach(entries::remove)
            writeGeneration += 1
            writeGeneration to entries.toMap()
        }
        writer.execute { write(snapshot.second, snapshot.first) }
    }

    private fun ensureLoaded(target: File): Boolean {
        if (loaded) return true
        val candidates = listOf(target, backupFile(target), tempFile(target))
        for ((index, candidate) in candidates.withIndex()) {
            val parsed = readEntries(candidate) ?: continue
            entries.clear()
            entries.putAll(parsed.entries.takeLast(maxMessages.coerceAtLeast(1)))
            loaded = true
            if (index != 0) {
                LoggingService.warn("AIRI_TRACE", "Recovered execution trace archive from ${candidate.name}")
                restoreRecoveredFile(candidate, target)
            }
            return true
        }
        // A corrupt archive must not poison every subsequent read forever. Keep
        // the in-memory view empty and wait for the next successful write.
        entries.clear()
        loaded = true
        if (target.exists() && target.length() > 0L) {
            LoggingService.error("AIRI_TRACE", "Execution trace archive was invalid; ignored without publishing partial entries")
        }
        return true
    }

    private fun write(snapshot: Map<Long, List<ExecutionTraceEvent>>, generation: Long) {
        val target = synchronized(lock) {
            if (generation != writeGeneration) return
            file
        } ?: return
        runCatching {
            val array = JSONArray()
            snapshot.forEach { (messageId, events) ->
                array.put(JSONObject().apply {
                    put("messageId", messageId)
                    put("events", JSONArray().also { list -> events.forEach { list.put(eventToJson(it)) } })
                })
            }
            target.parentFile?.mkdirs()
            val tmp = tempFile(target)
            val backup = backupFile(target)
            tmp.writeText(array.toString(), StandardCharsets.UTF_8)
            check(readEntries(tmp) != null) { "archive snapshot validation failed" }
            if (target.exists() && target.length() > 0L) {
                Files.copy(target.toPath(), backup.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            try {
                Files.move(
                    tmp.toPath(),
                    target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (atomicFailure: Exception) {
                runCatching {
                    Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }.onFailure {
                    throw it
                }
            }
            tmp.delete()
        }.onFailure { error ->
            LoggingService.error("AIRI_TRACE", "Execution trace archive write failed", error)
            tempFile(target).delete()
        }
    }

    private fun readEntries(candidate: File): LinkedHashMap<Long, List<ExecutionTraceEvent>>? {
        if (!candidate.exists() || candidate.length() == 0L) return LinkedHashMap()
        return runCatching {
            val array = JSONArray(candidate.readText(StandardCharsets.UTF_8))
            LinkedHashMap<Long, List<ExecutionTraceEvent>>().also { loadedEntries ->
                for (i in 0 until array.length()) {
                    val item = array.getJSONObject(i)
                    val events = item.getJSONArray("events")
                    loadedEntries[item.getLong("messageId")] = (0 until events.length()).map {
                        eventFromJson(events.getJSONObject(it))
                    }
                }
            }
        }.getOrNull()
    }

    private fun restoreRecoveredFile(source: File, target: File) {
        runCatching {
            target.parentFile?.mkdirs()
            Files.copy(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }.onFailure { LoggingService.error("AIRI_TRACE", "Recovered archive could not be restored", it) }
    }

    private fun tempFile(target: File): File = File(target.parentFile, target.name + ".tmp")
    private fun backupFile(target: File): File = File(target.parentFile, target.name + ".bak")

    internal fun eventToJson(event: ExecutionTraceEvent): JSONObject = JSONObject().apply {
        put("executionId", event.executionId)
        put("sequence", event.sequence)
        put("timestampMs", event.timestampMs)
        put("kind", event.kind.name)
        put("summary", event.summary)
        event.detail?.let { put("detail", it) }
        event.actionId?.let { put("actionId", it) }
        event.durationMs?.let { put("durationMs", it) }
        event.errorCode?.let { put("errorCode", it) }
        put("sessionId", event.sessionId)
        event.machineTag?.let { put("machineTag", it) }
        event.parentId?.let { put("parentId", it) }
        put("inputParams", JSONObject().also { params -> event.inputParams.forEach { (key, value) -> params.put(key, value) } })
    }

    internal fun eventFromJson(json: JSONObject): ExecutionTraceEvent = ExecutionTraceEvent(
        executionId = json.getString("executionId"),
        sequence = json.getLong("sequence"),
        timestampMs = json.getLong("timestampMs"),
        kind = ExecutionTraceKind.valueOf(json.getString("kind")),
        summary = json.optString("summary"),
        detail = if (json.has("detail")) json.getString("detail") else null,
        actionId = if (json.has("actionId")) json.getString("actionId") else null,
        durationMs = if (json.has("durationMs")) json.getLong("durationMs") else null,
        errorCode = if (json.has("errorCode")) json.getString("errorCode") else null,
        sessionId = json.optString("sessionId"),
        machineTag = if (json.has("machineTag")) json.getString("machineTag") else null,
        parentId = if (json.has("parentId")) json.getString("parentId") else null,
        inputParams = json.optJSONObject("inputParams")?.let { params ->
            params.keys().asSequence().associateWith { key -> params.optString(key) }
        }.orEmpty(),
    )
}
