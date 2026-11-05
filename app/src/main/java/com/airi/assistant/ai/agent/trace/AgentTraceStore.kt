package com.airi.assistant.ai.agent.trace

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * File-backed persistence for already-redacted [AgentTrace]s.
 *
 * Isolated from Room on purpose: a failure here can never affect chat data,
 * and no schema migration is needed. Writes go to a temp file and are renamed
 * so a crash mid-write leaves the previous snapshot intact.
 */
class AgentTraceStore(private val file: File) {

    fun load(): List<AgentTrace> = runCatching {
        if (!file.exists()) return emptyList()
        val array = JSONArray(file.readText())
        (0 until array.length()).mapNotNull { index ->
            runCatching { traceFromJson(array.getJSONObject(index)) }.getOrNull()
        }
    }.onFailure { Log.e("AIRI_TRACE", "Agent trace store read failed", it) }
        .getOrDefault(emptyList())

    fun save(traces: List<AgentTrace>) {
        runCatching {
            val array = JSONArray()
            traces.forEach { array.put(traceToJson(it)) }
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(array.toString())
            try {
                Files.move(
                    tmp.toPath(),
                    file.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (atomicFailure: Exception) {
                // Some Android filesystems do not support ATOMIC_MOVE. A
                // fallback replacement must never delete the last known-good
                // snapshot before the replacement is known to be available.
                if (!tmp.renameTo(file)) throw atomicFailure
            }
        }.onFailure { Log.e("AIRI_TRACE", "Agent trace store write failed", it) }
    }

    fun clear() {
        runCatching { file.delete() }
            .onFailure { Log.e("AIRI_TRACE", "Agent trace store clear failed", it) }
    }

    internal companion object {
        fun traceToJson(trace: AgentTrace): JSONObject = JSONObject().apply {
            put("id", trace.id)
            put("originalInput", trace.originalInput)
            put("finalResult", trace.finalResult)
            put("success", trace.success)
            put("timestamp", trace.timestamp)
            put("executionId", trace.executionId)
            trace.messageId?.let { put("messageId", it) }
            put("sessionId", trace.sessionId)
            put("source", trace.source)
            trace.model?.let { put("model", it) }
            trace.provider?.let { put("provider", it) }
            trace.estimatedOutputTokens?.let { put("estimatedOutputTokens", it) }
            trace.firstVisibleTokenLatencyMs?.let { put("firstVisibleTokenLatencyMs", it) }
            put("truncated", trace.truncated)
            put("droppedSteps", trace.droppedSteps)
            put("steps", JSONArray().also { steps -> trace.steps.forEach { steps.put(stepToJson(it)) } })
        }

        fun traceFromJson(json: JSONObject): AgentTrace {
            val stepsJson = json.optJSONArray("steps") ?: JSONArray()
            return AgentTrace(
                id = json.getString("id"),
                originalInput = json.optString("originalInput"),
                steps = (0 until stepsJson.length()).map { stepFromJson(stepsJson.getJSONObject(it)) },
                finalResult = json.optString("finalResult"),
                success = json.optBoolean("success", false),
                timestamp = json.optLong("timestamp", System.currentTimeMillis()),
                executionId = json.optString("executionId"),
                messageId = if (json.has("messageId")) json.getLong("messageId") else null,
                sessionId = json.optString("sessionId"),
                source = json.optString("source", AgentTrace.SOURCE_SKILL),
                model = if (json.has("model")) json.getString("model") else null,
                provider = if (json.has("provider")) json.getString("provider") else null,
                estimatedOutputTokens = if (json.has("estimatedOutputTokens")) json.getInt("estimatedOutputTokens") else null,
                firstVisibleTokenLatencyMs = if (json.has("firstVisibleTokenLatencyMs")) json.getLong("firstVisibleTokenLatencyMs") else null,
                truncated = json.optBoolean("truncated", false),
                droppedSteps = json.optInt("droppedSteps", 0),
            )
        }

        private fun stepToJson(step: AgentStep): JSONObject = JSONObject().apply {
            put("stepIndex", step.stepIndex)
            put("type", step.type.name)
            put("name", step.name)
            put("inputParams", JSONObject().also { params -> step.inputParams.forEach { (k, v) -> params.put(k, v) } })
            put("outputSummary", step.outputSummary)
            put("success", step.success)
            step.error?.let { put("error", it) }
            put("durationMs", step.durationMs)
        }

        private fun stepFromJson(json: JSONObject): AgentStep {
            val paramsJson = json.optJSONObject("inputParams")
            val params = paramsJson?.keys()?.asSequence()?.associateWith { paramsJson.optString(it) }.orEmpty()
            return AgentStep(
                stepIndex = json.optInt("stepIndex"),
                type = runCatching { AgentStepType.valueOf(json.getString("type")) }.getOrDefault(AgentStepType.TASK_STEP),
                name = json.optString("name"),
                inputParams = params,
                outputSummary = json.optString("outputSummary"),
                success = json.optBoolean("success", true),
                error = if (json.has("error")) json.getString("error") else null,
                durationMs = json.optLong("durationMs", 0L),
            )
        }
    }
}
