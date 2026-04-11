package com.airi.assistant.ui.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.airi.assistant.agent.planning.BrainInput
import com.airi.assistant.core.CognitiveResult
import com.airi.assistant.core.UnifiedCognitiveLoop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Chat message data model
 */
data class ChatMessage(
    val text: String,
    val isUser: Boolean
)

/**
 * Agent execution state for live overlay
 */
data class AgentState(
    val isWorking: Boolean = false,
    val currentAction: String = "",
    val currentStep: Int = 0,
    val totalSteps: Int = 0
)

/**
 * ChatViewModel - Connects UI to UnifiedCognitiveLoop
 * 
 * This is the CRITICAL integration point where UI triggers the cognitive pipeline
 */
class ChatViewModel : ViewModel() {

    var messages by mutableStateOf(listOf<ChatMessage>())
        private set

    var agentState by mutableStateOf(AgentState())
        private set

    private val cognitiveLoop = UnifiedCognitiveLoop()

    /**
     * Send user message and process through cognitive loop
     * 
     * Flow: UI → CognitiveLoop → AI → Plan → Execution → Result → UI
     */
    fun sendMessage(input: String) {
        // Add user message to chat
        messages = messages + ChatMessage(input, true)

        // Show agent working state
        agentState = AgentState(true, "Processing input...")

        // Process through cognitive loop
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    // Update agent state
                    updateAgentState("Generating plan...")

                    // Create simple JSON plan for direct text input
                    val simplePlan = """
                    {
                      "goal": "$input",
                      "steps": [
                        {
                          "id": "1",
                          "action": "process",
                          "params": {"input": "$input"},
                          "depends_on": [],
                          "expected": "Response generated"
                        }
                      ]
                    }
                    """.trimIndent()

                    // Process through UnifiedCognitiveLoop
                    val result = cognitiveLoop.process(
                        input = BrainInput(text = input),
                        llmResponse = simplePlan
                    )

                    // Handle result
                    withContext(Dispatchers.Main) {
                        when (result) {
                            is CognitiveResult.Success -> {
                                messages = messages + ChatMessage(
                                    "✅ Task completed successfully!\n${result.results.size} steps executed.",
                                    isUser = false
                                )
                            }
                            is CognitiveResult.PartialSuccess -> {
                                val successCount = result.results.count { it.result.success }
                                messages = messages + ChatMessage(
                                    "⚠️ Partial success: $successCount/${result.results.size} steps completed.",
                                    isUser = false
                                )
                            }
                            is CognitiveResult.Failed -> {
                                messages = messages + ChatMessage(
                                    "❌ Task failed: ${result.reason}",
                                    isUser = false
                                )
                            }
                            is CognitiveResult.AwaitingConfirmation -> {
                                messages = messages + ChatMessage(
                                    "⏳ Awaiting confirmation for: ${result.plan.intent}",
                                    isUser = false
                                )
                            }
                            is CognitiveResult.Error -> {
                                messages = messages + ChatMessage(
                                    "❌ Error: ${result.message}",
                                    isUser = false
                                )
                            }
                        }

                        // Clear agent state
                        agentState = AgentState(false, "")
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    messages = messages + ChatMessage(
                        "❌ Error: ${e.message}",
                        isUser = false
                    )
                    agentState = AgentState(false, "")
                }
            }
        }
    }

    private suspend fun updateAgentState(action: String) {
        withContext(Dispatchers.Main) {
            agentState = agentState.copy(currentAction = action)
        }
    }
}
