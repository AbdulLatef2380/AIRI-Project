package com.airi.assistant.core

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
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
    private val entries = LinkedHashMap<Long, List<ExecutionTraceEvent>>()
    private val writer by lazy {
        Executors.newSingleThreadExecutor { r -> Thread(r, "execution-trace-archive").apply { isDaemon = true } }
    }

    fun attach(target: File) = synchronized(lock) {
        file = target
        loaded = false
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
            entries.toMap()
        }
        writer.execute { write(snapshot) }
    }

    /** Returns stored traces for the given message ids. Call off the main thread. */
    fun getAll(messageIds: Collection<Long>): Map<Long, List<ExecutionTraceEvent>> = synchronized(lock) {
        val target = file ?: return emptyMap()
        if (!ensureLoaded(target)) return emptyMap()
        messageIds.mapNotNull { id -> entries[id]?.let { id to it } }.toMap()
    }

    /** Removes all archived traces as part of account deletion. */
    fun clear() = synchronized(lock) {
        entries.clear()
        loaded = true
        file?.let { target ->
            runCatching { target.delete() }
                .onFailure { Log.e("AIRI_TRACE", "Execution trace archive clear failed", it) }
        }
    }

    private fun ensureLoaded(target: File): Boolean {
        if (loaded) return true
        return runCatching {
            if (!target.exists()) {
                loaded = true
                return@runCatching true
            }
            val array = JSONArray(target.readText())
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                val events = item.getJSONArray("events")
                entries[item.getLong("messageId")] = (0 until events.length()).map { eventFromJson(events.getJSONObject(it)) }
            }
            loaded = true
            true
        }.onFailure { Log.e("AIRI_TRACE", "Execution trace archive read failed", it) }
            .getOrDefault(false)
    }

    private fun write(snapshot: Map<Long, List<ExecutionTraceEvent>>) {
        val target = synchronized(lock) { file } ?: return
        runCatching {
            val array = JSONArray()
            snapshot.forEach { (messageId, events) ->
                array.put(JSONObject().apply {
                    put("messageId", messageId)
                    put("events", JSONArray().also { list -> events.forEach { list.put(eventToJson(it)) } })
                })
            }
            target.parentFile?.mkdirs()
            val tmp = File(target.parentFile, target.name + ".tmp")
            tmp.writeText(array.toString())
            if (!tmp.renameTo(target)) {
                target.delete()
                tmp.renameTo(target)
            }
        }
    }

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
    )
}
