package com.airi.assistant.ui.run

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.airi.assistant.ai.agent.trace.AgentTrace
import com.airi.assistant.ai.agent.trace.AgentTraceManager
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.sample

/**
 * Single read path for the run screen (Part 5 §6): one live Flow from the trace store, batched
 * so a streaming run recomposes at most every [BATCH_MS] instead of once per event.
 * It never writes events; only the agent loop does.
 */
class AgentRunViewModel(
    private val traces: Flow<List<AgentTrace>> = AgentTraceManager.instance.traces,
    private val onlineProvider: () -> Boolean = { true },
    /** Reads a run the in-memory store no longer holds (Room). Never throws; null when unknown. */
    private val restore: suspend (String) -> AgentTrace? = { id ->
        try {
            com.airi.assistant.data.run.AgentRunStore.loadTrace(id)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    },
) : ViewModel() {

    private val runId = MutableStateFlow<String?>(null)
    private val waitingApproval = MutableStateFlow(false)

    fun open(id: String) { runId.value = id }

    fun setWaitingApproval(waiting: Boolean) { waitingApproval.value = waiting }

    @OptIn(ExperimentalCoroutinesApi::class, kotlinx.coroutines.FlowPreview::class)
    val state: StateFlow<AgentRunScreenState> = runId
        .flatMapLatest { id ->
            if (id == null) {
                kotlinx.coroutines.flow.flowOf<AgentRunScreenState>(AgentRunScreenState.Loading)
            } else {
                // Looked up at most once per opened run, and only while the run is absent from memory.
                var restored: AgentTrace? = null
                var restoreTried = false
                combine(traces.sample(BATCH_MS), waitingApproval) { list, waiting -> list to waiting }
                    .map { (list, waiting) ->
                        val inMemory = list.any { it.id == id || it.traceId == id }
                        if (!inMemory && !restoreTried) {
                            restoreTried = true
                            restored = restore(id)
                        }
                        val all = if (inMemory) list else list + listOfNotNull(restored)
                        deriveRunScreenState(id, all, loaded = true, online = onlineProvider(), waitingApproval = waiting)
                    }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AgentRunScreenState.Loading)

    companion object {
        const val BATCH_MS = 150L
    }
}
