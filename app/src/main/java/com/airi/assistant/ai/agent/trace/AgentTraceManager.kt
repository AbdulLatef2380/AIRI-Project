package com.airi.assistant.ai.agent.trace

import com.airi.assistant.execution.privacy.PrivacyGuard
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class AgentTraceManager private constructor() {

    companion object {
        const val MAX_TRACES = 50
        const val MAX_STEPS  = 5

        val instance: AgentTraceManager by lazy { AgentTraceManager() }
    }

    private val _traces = MutableStateFlow<List<AgentTrace>>(emptyList())
    val traces: StateFlow<List<AgentTrace>> = _traces.asStateFlow()

    val latestTrace: AgentTrace? get() = _traces.value.lastOrNull()

    private val activeBuilders = ConcurrentHashMap<String, ActiveTrace>()

    private data class ActiveTrace(
        val base: AgentTrace,
        val steps: MutableList<AgentStep> = mutableListOf()
    )

    fun startTrace(originalInput: String): String {
        val traceId = UUID.randomUUID().toString()
        activeBuilders[traceId] = ActiveTrace(
            base = AgentTrace(id = traceId, originalInput = PrivacyGuard.redactForTrace(originalInput))
        )
        return traceId
    }

    fun addStep(traceId: String, step: AgentStep) {
        val builder = activeBuilders[traceId] ?: return
        if (builder.steps.size < MAX_STEPS) {
            builder.steps.add(
                step.copy(
                    inputParams = step.inputParams.mapValues { (key, value) -> PrivacyGuard.redactTraceField(key, value) },
                    outputSummary = PrivacyGuard.redactForTrace(step.outputSummary),
                    error = step.error?.let { PrivacyGuard.redactForTrace(it) },
                )
            )
        }
    }

    fun finalizeTrace(traceId: String, finalResult: String, success: Boolean) {
        val builder = activeBuilders.remove(traceId) ?: return
        val finalized = builder.base.copy(
            steps       = builder.steps.toList(),
            finalResult = PrivacyGuard.redactForTrace(finalResult, 300),
            success     = success
        )
        _traces.update { current ->
            (current + finalized).takeLast(MAX_TRACES)
        }
    }

    fun getTrace(traceId: String): AgentTrace? =
        _traces.value.find { it.id == traceId }

    fun clearTraces() {
        _traces.value = emptyList()
        activeBuilders.clear()
    }
}
