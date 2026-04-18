package com.airi.assistant.domain.customskill

import com.airi.assistant.domain.logging.LoggingService
import java.util.concurrent.ConcurrentHashMap

object SkillCircuitBreaker {

    private const val TAG = "SkillCircuitBreaker"
    const val MAX_FAILURES = 3
    const val COOLDOWN_MS = 60_000L

    private data class SkillHealthState(
        var failures: Int = 0,
        var lastFailure: Long = 0L,
        var openedAt: Long = 0L
    )

    private val states = ConcurrentHashMap<String, SkillHealthState>()

    fun canExecute(skillId: String): Boolean {
        val state = states[skillId] ?: return true
        if (state.failures < MAX_FAILURES) return true
        val elapsed = System.currentTimeMillis() - state.openedAt
        if (elapsed >= COOLDOWN_MS) {
            LoggingService.info(TAG, "Circuit closed for '$skillId' — cooldown elapsed, resetting")
            states.remove(skillId)
            return true
        }
        val remaining = (COOLDOWN_MS - elapsed) / 1000
        LoggingService.warn(TAG, "Circuit OPEN for '$skillId' — ${remaining}s remaining before retry allowed")
        return false
    }

    fun remainingCooldownSeconds(skillId: String): Long {
        val state = states[skillId] ?: return 0L
        if (state.failures < MAX_FAILURES) return 0L
        val elapsed = System.currentTimeMillis() - state.openedAt
        return ((COOLDOWN_MS - elapsed) / 1000).coerceAtLeast(0L)
    }

    fun recordSuccess(skillId: String) {
        if (states.remove(skillId) != null) {
            LoggingService.debug(TAG, "Skill '$skillId' succeeded — circuit reset")
        }
    }

    fun recordFailure(skillId: String) {
        val state = states.getOrPut(skillId) { SkillHealthState() }
        synchronized(state) {
            state.failures++
            state.lastFailure = System.currentTimeMillis()
            if (state.failures >= MAX_FAILURES) {
                state.openedAt = System.currentTimeMillis()
                LoggingService.warn(
                    TAG,
                    "Circuit OPENED for '$skillId' after ${state.failures} consecutive failures — disabled for ${COOLDOWN_MS / 1000}s"
                )
            } else {
                LoggingService.info(TAG, "Failure ${state.failures}/$MAX_FAILURES recorded for '$skillId'")
            }
        }
    }

    fun getHealthState(skillId: String): SkillHealth {
        val state = states[skillId]
        return when {
            state == null -> SkillHealth.HEALTHY
            state.failures < MAX_FAILURES -> SkillHealth.DEGRADED
            else -> {
                val remaining = remainingCooldownSeconds(skillId)
                if (remaining > 0) SkillHealth.OPEN else SkillHealth.HEALTHY
            }
        }
    }

    enum class SkillHealth { HEALTHY, DEGRADED, OPEN }
}
