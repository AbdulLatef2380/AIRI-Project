package com.airi.assistant.agent.decision

interface RiskProvider {
    fun estimate(action: String): RiskResult
}
