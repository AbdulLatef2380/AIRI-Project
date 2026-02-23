package com.airi.core.loop

import com.airi.core.model.*

interface CognitiveLoop {

    suspend fun perceive(input: String): IntentData

    suspend fun buildContext(intent: IntentData): ContextSnapshot

    suspend fun reason(context: ContextSnapshot): DecisionResult

    suspend fun act(decision: DecisionResult)
}
