package com.airi.assistant.domain.monetization

import android.content.Context
import android.util.Log
import com.airi.assistant.domain.event.AppEvent
import com.airi.assistant.domain.event.EventBus
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * CreditMeteringEngine — full consumption metering loop for all agent actions.
 *
 * REAL EXECUTION:
 *   - Tracks credit consumption per [ActionType] with configurable per-action
 *     weights (messages are cheap; image generation is expensive).
 *   - Persists daily and lifetime totals in SharedPreferences (JSON).
 *   - Emits [AppEvent.UsageLimitReached] when the daily credit budget
 *     is exhausted for the current [SubscriptionTier].
 *   - [consume] is the single call site — every agent routing path calls
 *     it before executing to both check and decrement credits atomically.
 *   - Integrates with [SubscriptionManager]: premium users have a higher
 *     daily credit budget and lower per-action weights.
 *
 * WIRING:
 *   - [ServiceLocator.creditMeteringEngine] holds the singleton.
 *   - [ProductionAgentOrchestrator] calls [consume] at the start of
 *     every sub-agent execution.
 *   - [ChatViewModel.sendMessage] calls [consume(ActionType.MESSAGE)]
 *     before dispatching.
 */
class CreditMeteringEngine(
    private val context: Context,
    private val subscriptionManager: SubscriptionManager
) {

    companion object {
        private const val TAG        = "CreditMeteringEngine"
        private const val PREFS_NAME = "airi_credit_meter"
        private const val KEY_DATA   = "meter_v2"
        private const val DATE_FMT   = "yyyy-MM-dd"

        // ── Per-action credit weights ──────────────────────────────────────────
        // Free / Premium weights. Premium weights are 50% of free to reward upgrades.
        private val FREE_WEIGHTS: Map<ActionType, Int> = mapOf(
            ActionType.MESSAGE          to 1,
            ActionType.AGENT_EXECUTION  to 3,
            ActionType.SKILL_USE        to 2,
            ActionType.IMAGE_GENERATION to 10,
            ActionType.DOCUMENT_PROCESS to 4,
            ActionType.BROWSER_FETCH    to 3,
            ActionType.SCHEDULED_JOB    to 1,
            ActionType.RAG_RETRIEVAL    to 1
        )
        private val PREMIUM_WEIGHTS: Map<ActionType, Int> = FREE_WEIGHTS.mapValues {
            (it.value / 2).coerceAtLeast(1)
        }

        private const val FREE_DAILY_CREDITS    = 200
        private const val PREMIUM_DAILY_CREDITS = 2_000
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ── Public API ─────────────────────────────────────────────────────────────

    /**
     * Check and consume credits for [action].
     *
     * Synchronized to prevent a read-modify-write race: without the lock,
     * two concurrent calls both read the same [MeterData.dailyTotal] before
     * either writes back, allowing both to pass the budget check and causing
     * double-spend (budget appears to have more credits than it does).
     * This is especially relevant during parallel SubAgent execution waves.
     *
     * @return [ConsumeResult.Allowed] if the action is permitted and credits
     *         were decremented, [ConsumeResult.Denied] if the daily budget
     *         is exhausted.
     */
    @Synchronized
    fun consume(action: ActionType): ConsumeResult {
        val meter   = loadMeter()
        val premium = subscriptionManager.isPremium()
        val weight  = if (premium) PREMIUM_WEIGHTS[action] ?: 1
                      else         FREE_WEIGHTS[action]    ?: 1
        val budget  = if (premium) PREMIUM_DAILY_CREDITS else FREE_DAILY_CREDITS

        val remaining = CreditBudgetPolicy.remaining(meter.dailyTotal, budget)
        if (!CreditBudgetPolicy.canConsume(meter.dailyTotal, budget, weight)) {
            val denial = ConsumeResult.Denied(
                action         = action,
                dailyTotal     = meter.dailyTotal,
                budget         = budget,
                shortfallCredit = weight - remaining
            )
            EventBus.emitSync(AppEvent.UsageLimitReached(
                "daily_credits",
                meter.dailyTotal,
                budget
            ))
            Log.w(TAG, "Credit denied action=${action.name} used=${meter.dailyTotal}/$budget")
            return denial
        }

        val updated = meter.copy(
            dailyTotal    = meter.dailyTotal + weight,
            lifetimeTotal = meter.lifetimeTotal + weight,
            perActionDay  = meter.perActionDay.toMutableMap().also { m ->
                m[action.name] = (m[action.name] ?: 0) + weight
            }
        )
        saveMeter(updated)

        Log.d(TAG, "Credit consumed action=${action.name} weight=$weight used=${updated.dailyTotal}/$budget")

        // Soft-limit checkpoints
        val usedFraction = updated.dailyTotal.toFloat() / budget.toFloat()
        when {
            usedFraction >= 0.9f && (updated.dailyTotal - weight).toFloat() / budget < 0.9f ->
                EventBus.emitSync(AppEvent.UsageLimitReached("daily_credits_90pct", updated.dailyTotal, budget))
            usedFraction >= 0.75f && (updated.dailyTotal - weight).toFloat() / budget < 0.75f ->
                EventBus.emitSync(AppEvent.UsageLimitReached("daily_credits_75pct", updated.dailyTotal, budget))
        }

        return ConsumeResult.Allowed(
            action      = action,
            creditsUsed = weight,
            dailyTotal  = updated.dailyTotal,
            budget      = budget,
                        remaining     = CreditBudgetPolicy.remaining(updated.dailyTotal, budget)
        )
    }

    /** Peek at the current state without consuming any credits. */
    @Synchronized
    fun snapshot(): MeterSnapshot {
        val meter   = loadMeter()
        val premium = subscriptionManager.isPremium()
        val budget  = if (premium) PREMIUM_DAILY_CREDITS else FREE_DAILY_CREDITS
        return MeterSnapshot(
            dailyTotal    = meter.dailyTotal,
            lifetimeTotal = meter.lifetimeTotal,
            budget        = budget,
            remaining     = CreditBudgetPolicy.remaining(meter.dailyTotal, budget),
            usedFraction  = CreditBudgetPolicy.clampedUsed(meter.dailyTotal, budget).toFloat() / budget.toFloat(),
            perActionDay  = meter.perActionDay.mapKeys {
                runCatching { ActionType.valueOf(it.key) }.getOrNull() ?: ActionType.MESSAGE
            }
        )
    }

    /** Force-reset the daily counter (used by tests and admin screens). */
    @Synchronized
    fun resetDailyCounters() {
        val meter   = loadMeter()
        saveMeter(meter.copy(dailyTotal = 0, perActionDay = emptyMap(), date = today()))
        Log.i(TAG, "AIRI CREDIT_METER_RESET")
    }

    // ── Persistence ────────────────────────────────────────────────────────────

    private fun loadMeter(): MeterData {
        val raw = prefs.getString(KEY_DATA, null) ?: return MeterData(date = today())
        val parsed = runCatching {
            val json  = JSONObject(raw)
            val saved = json.getString("date")
            if (saved != today()) {
                // New day — reset daily counters but keep lifetime.
                MeterData(
                    date          = today(),
                    dailyTotal    = 0,
                    lifetimeTotal = json.optLong("lifetime_total", 0L),
                    perActionDay  = emptyMap()
                )
            } else {
                val perAction = mutableMapOf<String, Int>()
                val perObj    = json.optJSONObject("per_action") ?: JSONObject()
                perObj.keys().forEach { k -> perAction[k] = perObj.getInt(k) }
                MeterData(
                    date          = saved,
                    dailyTotal    = json.getInt("daily_total"),
                    lifetimeTotal = json.getLong("lifetime_total"),
                    perActionDay  = perAction
                )
            }
        }.getOrDefault(MeterData(date = today()))
        val migration = CreditBudgetPolicy.removeLegacyTokenCharges(
            dailyTotal = parsed.dailyTotal,
            lifetimeTotal = parsed.lifetimeTotal,
            perActionDay = parsed.perActionDay,
        )
        if (!migration.migrated) return parsed
        return parsed.copy(
            dailyTotal = migration.dailyTotal,
            lifetimeTotal = migration.lifetimeTotal,
            perActionDay = migration.perActionDay,
        ).also { saveMeter(it) }
    }

    private fun saveMeter(data: MeterData) {
        val perObj = JSONObject()
        data.perActionDay.forEach { (k, v) -> perObj.put(k, v) }
        val json = JSONObject().apply {
            put("date",           data.date)
            put("daily_total",    data.dailyTotal)
            put("lifetime_total", data.lifetimeTotal)
            put("per_action",     perObj)
        }
        prefs.edit().putString(KEY_DATA, json.toString()).apply()
    }


    private fun today(): String = SimpleDateFormat(DATE_FMT, Locale.getDefault()).format(Date())
    private data class MeterData(
        val date:          String,
        val dailyTotal:    Int             = 0,
        val lifetimeTotal: Long            = 0L,
        val perActionDay:  Map<String, Int> = emptyMap()
    )
}

// ── Domain types ───────────────────────────────────────────────────────────────

enum class ActionType {
    MESSAGE, AGENT_EXECUTION, SKILL_USE, IMAGE_GENERATION,
    DOCUMENT_PROCESS, BROWSER_FETCH, SCHEDULED_JOB, RAG_RETRIEVAL
}

sealed class ConsumeResult {
    data class Allowed(
        val action:      ActionType,
        val creditsUsed: Int,
        val dailyTotal:  Int,
        val budget:      Int,
        val remaining:   Int
    ) : ConsumeResult()

    data class Denied(
        val action:          ActionType,
        val dailyTotal:      Int,
        val budget:          Int,
        val shortfallCredit: Int
    ) : ConsumeResult() {
        val userMessage: String get() =
            "You've used your daily credit allowance ($dailyTotal/$budget). " +
            "Upgrade to Premium for 10× more credits, or wait until tomorrow."
    }
}

data class MeterSnapshot(
    val dailyTotal:    Int,
    val lifetimeTotal: Long,
    val budget:        Int,
    val remaining:     Int,
    val usedFraction:  Float,
    val perActionDay:  Map<ActionType, Int>
)
