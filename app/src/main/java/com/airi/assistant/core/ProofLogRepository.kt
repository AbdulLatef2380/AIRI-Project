package com.airi.assistant.core

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * ProofLogRepository — streams live AIRI_PROOF logcat events into a
 * [StateFlow] of [ProofLogEntry] for in-app display.
 *
 * ## How it works
 *
 * Spawns `logcat -v time -s AIRI_PROOF:V` as a persistent process
 * and reads its stdout line-by-line in a background coroutine. Each
 * line is parsed into a [ProofLogEntry] and prepended to [entries].
 * A hard cap of [MAX_ENTRIES] prevents unbounded memory growth.
 *
 * ## Lifecycle
 *
 * Call [start] once (e.g. when the AgentLogViewer becomes visible) and
 * [stop] when it's no longer needed. [start] is idempotent — calling it
 * twice only starts one background reader. [stop] destroys the logcat
 * process and cancels the reader coroutine.
 *
 * ## Entry format
 *
 * Logcat `-v time` output:
 * ```
 * MM-DD HH:MM:SS.mmm  PID  TID I AIRI_PROOF: INF_START id=a1b2 ...
 * ```
 *
 * [ProofLogEntry.parse] extracts the log level, event name, and key=value
 * data from each matching line.
 *
 * ## Permissions
 *
 * On Android < 4.1 (API < 16), reading logcat requires READ_LOGS permission.
 * Modern Android restricts logcat to the app's own PID, which is exactly
 * what we want — AIRI_PROOF events are emitted by the same process.
 */
class ProofLogRepository {

    // ── Public model ──────────────────────────────────────────────────────────

    /**
     * A single parsed AIRI_PROOF log entry.
     *
     * @param id        Monotonically increasing ID for stable list keys.
     * @param rawLine   Full logcat line for copy/export.
     * @param event     Event name (e.g. `INF_START`, `GRAPH_NODE_DONE`).
     * @param data      Structured key=value payload after the event name.
     * @param level     Logcat level character: `I`, `W`, `E`, `D`, `V`.
     * @param family    Event family prefix for filtering (e.g. `INF`, `GRAPH`).
     * @param timeLabel Human-readable `HH:MM:SS` timestamp from the logcat line.
     */
    data class ProofLogEntry(
        val id:        Long,
        val rawLine:   String,
        val event:     String,
        val data:      String,
        val level:     String,
        val family:    String,
        val timeLabel: String,
    )

    // ── State ─────────────────────────────────────────────────────────────────

    private val _entries = MutableStateFlow<List<ProofLogEntry>>(emptyList())
    val entries: StateFlow<List<ProofLogEntry>> = _entries.asStateFlow()

    private val _isStreaming = MutableStateFlow(false)
    val isStreaming: StateFlow<Boolean> = _isStreaming.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    // ── Infrastructure ────────────────────────────────────────────────────────

    private val scope  = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var job: Job? = null
    private var process: Process? = null
    private var entryCounter = 0L

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    /**
     * Start streaming AIRI_PROOF events from logcat.
     * Idempotent — safe to call when already streaming.
     */
    fun start() {
        if (job?.isActive == true) return
        _errorMessage.value = null
        _isStreaming.value  = true
        job = scope.launch {
            runCatching { stream() }.onFailure { t ->
                Log.w("AIRI_PROOF", "PROOF_LOG_STREAM_ERROR cause=${t.message}")
                _errorMessage.value = t.message ?: "Stream error"
            }
            _isStreaming.value = false
        }
        Log.i("AIRI_PROOF", "PROOF_LOG_STREAM_START")
    }

    /** Stop streaming and release the logcat process. */
    fun stop() {
        job?.cancel()
        job = null
        runCatching { process?.destroy() }
        process = null
        _isStreaming.value = false
        Log.i("AIRI_PROOF", "PROOF_LOG_STREAM_STOP entries=${_entries.value.size}")
    }

    /** Clear all accumulated entries. */
    fun clear() {
        _entries.value = emptyList()
        entryCounter   = 0L
        Log.i("AIRI_PROOF", "PROOF_LOG_CLEARED")
    }

    // ── Stream loop ───────────────────────────────────────────────────────────

    private fun stream() {
        // -v time  → "MM-DD HH:MM:SS.mmm  PID  TID LEVEL/TAG: message"
        // -s AIRI_PROOF:V  → filter to AIRI_PROOF tag at all levels
        // No -d flag → persistent streaming process (not dump-and-exit)
        val cmd = listOf("logcat", "-v", "time", "-s", "AIRI_PROOF:V")
        process = ProcessBuilder(cmd)
            .redirectErrorStream(false)
            .start()

        val reader = BufferedReader(InputStreamReader(process!!.inputStream))
        try {
            var raw: String?
            while (reader.readLine().also { raw = it } != null) {
                val line = raw ?: continue
                if (!line.contains("AIRI_PROOF")) continue
                val entry = parse(line) ?: continue
                _entries.value = (_entries.value + entry).takeLast(MAX_ENTRIES)
            }
        } finally {
            reader.close()
        }
    }

    // ── Parsing ───────────────────────────────────────────────────────────────

    private fun parse(raw: String): ProofLogEntry? {
        // logcat -v time line format:
        // "MM-DD HH:MM:SS.mmm  PID  TID I/AIRI_PROOF: INF_START id=abc ..."
        // or (newer format):
        // "MM-DD HH:MM:SS.mmm  PID  TID I AIRI_PROOF: INF_START id=abc ..."
        val tagIdx = raw.indexOf("AIRI_PROOF")
        if (tagIdx < 0) return null

        // Extract level char — search backwards from tagIdx for W/I/E/D
        val level = runCatching {
            val before = raw.substring(maxOf(0, tagIdx - 4), tagIdx).trimEnd('/', ' ')
            when {
                before.endsWith("E") -> "E"
                before.endsWith("W") -> "W"
                before.endsWith("D") -> "D"
                else -> "I"
            }
        }.getOrDefault("I")

        // Extract human-readable timestamp (first 18 chars = "MM-DD HH:MM:SS.mmm" for -v time)
        val timeLabel = runCatching {
            // format: "MM-DD HH:MM:SS.mmm"
            raw.take(18).trimStart()
        }.getOrDefault("")

        // Message is everything after "AIRI_PROOF: " or "AIRI_PROOF "
        val msgStart = tagIdx + "AIRI_PROOF".length
        val msg = raw.substring(msgStart).trimStart(':', ' ')

        // Split "EVENT_NAME key1=val1 key2=val2 ..."
        val spaceIdx = msg.indexOf(' ')
        val event = if (spaceIdx > 0) msg.substring(0, spaceIdx) else msg
        val data  = if (spaceIdx > 0) msg.substring(spaceIdx + 1) else ""

        // Event family = prefix before first underscore
        val underscoreIdx = event.indexOf('_')
        val family = if (underscoreIdx > 0) event.substring(0, underscoreIdx) else event

        return ProofLogEntry(
            id        = ++entryCounter,
            rawLine   = raw,
            event     = event,
            data      = data,
            level     = level,
            family    = family,
            timeLabel = timeLabel,
        )
    }

    companion object {
        private const val MAX_ENTRIES = 500

        /** All known AIRI_PROOF event families for filter chips. */
        val FAMILIES = listOf("ALL", "INF", "GRAPH", "CONNECTOR", "CLOUD", "VOICE", "MEMORY", "SESSION")

        val instance = ProofLogRepository()
    }
}
